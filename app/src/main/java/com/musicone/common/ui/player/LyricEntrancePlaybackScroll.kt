package com.musicone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** 歌词展开入场时共享列表滚动权：入场负责把列表送回阅读锚点，期间的自然换句叠加在同一个滚动会话里。 */
internal class LyricEntrancePlaybackScroll(
    private val originLine: Int,
    private val originOffset: Float,
    private val scrollBy: (Float) -> Float,
    private val lease: () -> Boolean = { true },
) {
    private val entrance = LyricEntranceAlignmentSession()
    private var attached = true
    var remainingOffset by mutableFloatStateOf(originOffset)
        private set
    var playbackUsers by mutableIntStateOf(0)
        private set

    fun canScroll(): Boolean = attached && lease()

    fun moveEntrance(progress: Float) {
        if (!canScroll()) return
        entrance.moveTo(originLine, progress, originOffset, measured = true) { delta ->
            scrollBy(delta).also { remainingOffset -= it }
        }
    }

    fun playbackTravel(actualOffset: Float): Float = actualOffset - remainingOffset

    suspend fun scrollPlayback(block: suspend ((Float) -> Float) -> Unit) {
        if (!canScroll()) return
        playbackUsers++
        try {
            block { delta -> if (canScroll()) scrollBy(delta) else 0f }
        } finally { playbackUsers-- }
    }

    fun release() { attached = false }
}
