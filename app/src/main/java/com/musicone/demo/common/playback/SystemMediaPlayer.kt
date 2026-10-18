package com.musicone.demo

import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer.State
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** 系统控件复用业务播放意图与队列，状态和监听事件仍由真实播放器提供。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class SystemMediaPlayer(
    delegate: Player,
    private val setPlaying: (Boolean) -> Unit,
    private val previous: () -> Unit,
    private val next: () -> Unit,
    private val seek: (String, Long) -> Unit,
    private val recordCommand: (String) -> Unit,
) : ForwardingSimpleBasePlayer(delegate) {
    override fun getState(): State {
        val state = super.getState()
        // 业务队列由运行对象持有，不能根据 ExoPlayer 的单项时间线隐藏切歌按钮。
        val commands = state.availableCommands.buildUpon()
        if (getPlayer().currentMediaItem != null) {
            commands.addAll(
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            )
        }
        return state.buildUpon().setAvailableCommands(commands.build()).build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        recordCommand(if (playWhenReady) "播放" else "暂停")
        setPlaying(playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val delegate = getPlayer()
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                recordCommand("上一首")
                previous()
            }
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                recordCommand("下一首")
                next()
            }
            else -> {
                if (mediaItemIndex != C.INDEX_UNSET && mediaItemIndex != delegate.currentMediaItemIndex) {
                    return Futures.immediateVoidFuture()
                }
                val mediaId = delegate.currentMediaItem?.mediaId ?: return Futures.immediateVoidFuture()
                val target = when (seekCommand) {
                    Player.COMMAND_SEEK_BACK -> delegate.currentPosition - delegate.seekBackIncrement
                    Player.COMMAND_SEEK_FORWARD -> delegate.currentPosition + delegate.seekForwardIncrement
                    Player.COMMAND_SEEK_TO_DEFAULT_POSITION -> 0L
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM ->
                        positionMs.takeUnless { it == C.TIME_UNSET } ?: 0L
                    else -> return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
                }
                recordCommand("跳转")
                seek(mediaId, target.coerceAtLeast(0L))
            }
        }
        return Futures.immediateVoidFuture()
    }
}
