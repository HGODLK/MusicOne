package com.musicone

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** 转屏模糊共用正文已经算好的焦点段，不依赖被暂停的普通开合行程采样。 */
internal class PhoneLyricsTurnFocus {
    private data class Frame(val generation: Int, val height: Int, val edges: LyricBoundaryEdges)
    private var frame by mutableStateOf<Frame?>(null)

    fun record(generation: Int, height: Int, viewportStart: Int, item: LazyListItemInfo?,
        offset: Float, stable: Boolean) {
        frame = item?.takeIf { stable }?.let {
            val top = it.offset - viewportStart + offset
            Frame(generation, height, LyricBoundaryEdges(top, top + it.size))
        }
    }

    fun read(generation: Int, height: Float): LyricBoundaryEdges? = frame?.takeIf {
        it.generation == generation && it.height.toFloat() == height
    }?.edges
}

internal val LocalPhoneLyricsTurnFocus = staticCompositionLocalOf<PhoneLyricsTurnFocus?> { null }

/** 只平移原模糊带，保留带宽、曲线和互补权重，让转屏中的焦点整段保持清晰。 */
internal fun PhoneLyricsBackdropFrame.protectFocus(focus: LyricBoundaryEdges?, gap: Float): PhoneLyricsBackdropFrame {
    if (focus == null) return this
    val upperShift = (focus.top - gap - topEnd).coerceAtMost(0f)
    val lowerShift = (focus.bottom + gap - bottomStart).coerceAtLeast(0f)
    return copy(top = top + upperShift, topEnd = topEnd + upperShift,
        bottomStart = bottomStart + lowerShift, bottomEnd = bottomEnd + lowerShift)
}
