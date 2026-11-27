package com.musicone

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 专门负责音频焦点的开关，不影响媒体会话、播放进度和耳机拔出暂停逻辑。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PlaybackAudioFocusCoordinator(private val player: ExoPlayer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun start() {
        scope.launch {
            DevicePlaybackOptions.concurrentPlayback.collect { enabled ->
                player.setAudioAttributes(player.audioAttributes, !enabled)
            }
        }
    }

    fun release() = scope.cancel()
}
