package com.musicone.demo

import android.util.Log
import androidx.media3.common.Player

/** 只记录输送状态和停止原因，便于区分息屏后的焦点丢失、缓冲与 USB 故障。 */
internal class PlaybackServiceDiagnostics(private val foreground: () -> Boolean) : Player.Listener {
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        Log.i("MusicPlaybackService", "播放意图=$playWhenReady 原因=$reason")
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (!events.containsAny(Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED)) return
        Log.i("MusicPlaybackService", "前台服务=${foreground()} 状态=${player.playbackState}" +
            " 播放意图=${player.playWhenReady} 抑制原因=${player.playbackSuppressionReason}" +
            " USB路由=${UsbAudioOutputStatus.current.value.route}")
    }
}
