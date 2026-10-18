package com.musicone.demo

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 只调整音频焦点，不改变媒体会话、播放位置和耳机拔出处理。 */
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
