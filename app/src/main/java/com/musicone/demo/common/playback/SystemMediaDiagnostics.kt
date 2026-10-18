package com.musicone.demo

import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession

/** 区分会话发布、外部连接与指令到达；不记录歌名、音源地址或完整媒体标识。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class SystemMediaDiagnostics : MediaSession.Callback, Player.Listener {
    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        Log.i(TAG, "连接请求 来源=${controller.packageName} 受信任=${controller.isTrusted}")
        // 保留 Media3 默认授信策略，不能因厂商包名不同而拒绝系统控制器。
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
    }

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        Log.i(TAG, "连接断开 来源=${controller.packageName}")
    }

    fun command(name: String, controller: MediaSession.ControllerInfo?) {
        Log.i(TAG, "指令=$name 来源=${controller?.packageName ?: "未知"}")
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (!events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_MEDIA_METADATA_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_AVAILABLE_COMMANDS_CHANGED)) return
        val metadata = player.mediaMetadata
        Log.i(TAG, "会话状态=${player.playbackState} 播放意图=${player.playWhenReady}" +
            " 歌曲=${player.currentMediaItem?.mediaId?.hashCode()} 标题=${!metadata.title.isNullOrBlank()}" +
            " 封面=${metadata.artworkUri != null || metadata.artworkData != null} 时长=${player.duration}" +
            " 播放暂停=${player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)}" +
            " 上一首=${player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)}" +
            " 下一首=${player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)}" +
            " 跳转=${player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)}")
    }

    private companion object { const val TAG = "MusicOneSystemMedia" }
}
