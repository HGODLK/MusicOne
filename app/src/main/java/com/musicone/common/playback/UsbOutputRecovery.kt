package com.musicone

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink

/** USB 输出异常单独处理，不要当成歌曲播不了去刷新地址或自动跳下一首。 */
internal class UsbOutputException(cause: Throwable) : IllegalStateException("USB 音频输出已中断", cause)

@OptIn(UnstableApi::class)
internal fun isUsbOutputFailure(
    error: Throwable,
    exclusiveEnabled: Boolean,
    directRouteActive: Boolean = UsbAudioOutputStatus.directOutputActive,
): Boolean =
    generateSequence(error) { it.cause }.any {
        it is UsbOutputException || exclusiveEnabled && directRouteActive &&
            (it is AudioSink.WriteException || it is AudioSink.InitializationException)
    }

/** 等待旧 USB 输出释放期间，如果用户切了歌或点了暂停，以用户最新操作为准。 */
internal suspend fun reprepareUsbRoute(
    player: Player,
    awaitSystemRoute: suspend () -> Unit = {},
    awaitReset: suspend () -> Unit,
) {
    val item = player.currentMediaItem ?: return
    val index = player.currentMediaItemIndex
    val position = player.currentPosition
    val wasIdle = player.playbackState == Player.STATE_IDLE
    player.stop()
    if (!wasIdle) awaitReset()
    awaitSystemRoute()
    if (player.currentMediaItem != item || player.currentMediaItemIndex != index) return
    player.seekTo(position)
    player.prepare()
}

/** 必须在写线程退出后调用；如果流释放失败，先关掉 USB 设备连接再释放底层缓冲。 */
internal fun releaseUsbStream(release: () -> Unit, closeDevice: () -> Unit, releaseAfterClose: () -> Unit) {
    try {
        release()
    } catch (_: IllegalStateException) {
        closeDevice()
        releaseAfterClose()
        return
    }
    closeDevice()
}
