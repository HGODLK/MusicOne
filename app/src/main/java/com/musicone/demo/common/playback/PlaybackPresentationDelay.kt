package com.musicone.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class PlaybackPresentationKey(
    val trackId: String,
    val generation: Long,
)

/**
 * 将短暂缓冲与用户可见的暂停状态隔离；手动播放/暂停仍立即交接。
 */
internal class PlaybackPresentationDelay(
    private val scope: CoroutineScope,
    private val stillStopped: (PlaybackPresentationKey) -> Boolean,
    private val publish: (PlaybackPresentationKey, Boolean) -> Unit,
    private val delayMs: Long = PLAYBACK_PAUSE_PRESENTATION_DELAY_MS,
) {
    private var pendingStop: Job? = null

    fun present(key: PlaybackPresentationKey, playing: Boolean) {
        pendingStop?.cancel()
        pendingStop = null
        publish(key, playing)
    }

    fun transportStopped(key: PlaybackPresentationKey) {
        pendingStop?.cancel()
        pendingStop = scope.launch {
            delay(delayMs)
            if (stillStopped(key)) publish(key, false)
        }
    }

    fun cancel() {
        pendingStop?.cancel()
        pendingStop = null
    }
}

internal const val PLAYBACK_PAUSE_PRESENTATION_DELAY_MS = 1_000L
