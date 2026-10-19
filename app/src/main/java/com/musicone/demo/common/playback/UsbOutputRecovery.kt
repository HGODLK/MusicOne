package com.musicone.demo

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink

/** 输出设备错误单独交接，不进入歌曲换票或跳过流程。 */
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

/** 等待旧输出释放期间，用户的新曲目和播放意图始终优先。 */
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

/** 写线程退出后才可调用；回收失败先关闭拥有该文件描述符的连接，再释放原生缓冲。 */
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
