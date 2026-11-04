package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** 只保存模糊边界和权重，字形仍由同一份实时歌词图层提供。 */
internal data class PhoneLyricsBackdropFrame(
    val top: Float, val topEnd: Float, val peak: Float,
    val bottomStart: Float, val bottomEnd: Float, val pivot: Float,
    val strength: Float = 1f,
) {
    fun atPivot(value: Float): PhoneLyricsBackdropFrame {
        val shift = value - pivot
        return copy(top = top + shift, topEnd = topEnd + shift,
            bottomStart = bottomStart + shift, bottomEnd = bottomEnd + shift, pivot = value)
    }
}

internal class PhoneLyricsBackdropTurn {
    private var last: PhoneLyricsBackdropFrame? = null
    private var source: PhoneLyricsBackdropFrame? = null
    val retained get() = source != null
    val sourceTopHeight get() = source?.let { it.topEnd - it.top } ?: 0f

    fun capture() { source = last?.takeIf { it.strength > 0f } }
    fun clear() { last = null; source = null }

    fun frame(target: PhoneLyricsBackdropFrame, landscape: Boolean, changing: Boolean,
        progress: Float, focus: LyricBoundaryEdges? = null, focusGap: Float = 0f): PhoneLyricsBackdropFrame {
        if (!changing) source = null
        val origin = source?.takeIf { changing }?.atPivot(target.pivot)
        val p = progress.coerceIn(0f, 1f)
        fun mix(a: Float, b: Float) = a + (b - a) * p
        val mixed = when {
            origin == null -> target.copy(strength = if (landscape) 0f else 1f)
            landscape -> origin.copy(strength = origin.strength * (1f - p))
            // 只续接尚未结束的竖转横；稳定横屏切回竖屏仍用原来的表现。
            else -> target.copy(top = mix(origin.top, target.top), topEnd = mix(origin.topEnd, target.topEnd),
                peak = mix(origin.peak, target.peak), bottomStart = mix(origin.bottomStart, target.bottomStart),
                bottomEnd = mix(origin.bottomEnd, target.bottomEnd), strength = mix(origin.strength, 1f))
        }
        val result = if (changing && landscape) mixed.protectFocus(focus, focusGap) else mixed
        last = result
        return result
    }
}

@Composable
internal fun rememberPhoneLyricsBackdropTurn(turn: PhonePlayerTurn?): PhoneLyricsBackdropTurn? {
    if (turn == null) return null
    val state = remember(turn) { PhoneLyricsBackdropTurn() }
    DisposableEffect(turn, state) {
        turn.beforeLayoutChange(state, state::capture)
        onDispose { turn.removeLayoutChangeListener(state) }
    }
    return state
}

internal fun lyricBackdropClearAlpha(original: Float, strength: Float): Float =
    1f - (1f - original) * strength.coerceIn(0f, 1f)

internal fun lyricBackdropTopMask(blurred: Boolean, canBlur: Boolean,
    peak: Float, height: Float, strength: Float): Brush {
    val stops = when {
        blurred -> arrayOf(0f to 0f, peak * .25f to .06f, peak * .55f to .4f,
            peak * .8f to .78f, peak to 1f, 1f to 0f)
        canBlur -> arrayOf(0f to 0f, peak to 0f, 1f to 1f)
        else -> arrayOf(0f to 0f, peak * .25f to .06f * LYRIC_BACKDROP_FALLBACK_ALPHA,
            peak * .55f to .4f * LYRIC_BACKDROP_FALLBACK_ALPHA,
            peak * .8f to .78f * LYRIC_BACKDROP_FALLBACK_ALPHA,
            peak to LYRIC_BACKDROP_FALLBACK_ALPHA, 1f to 1f)
    }
    return Brush.verticalGradient(*stops.map { (position, alpha) -> position to
        Color.White.copy(alpha = if (blurred) alpha else lyricBackdropClearAlpha(alpha, strength))
    }.toTypedArray(), startY = 0f, endY = height.coerceAtLeast(1f))
}
