package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

internal data class PhoneLyricsFadeFrame(val top: Float, val bottom: Float,
    val topHeight: Float, val bottomHeight: Float, val pivot: Float, val strength: Float) {
    fun atPivot(value: Float) = copy(top = top + value - pivot,
        bottom = bottom + value - pivot, pivot = value)
}

/** 转屏期间随转向进度平滑过渡上下渐隐遮罩，中途反向时从当前实际权重续接。 */
internal class PhoneLyricsFadeTurn {
    private var last: PhoneLyricsFadeFrame? = null
    private var source: PhoneLyricsFadeFrame? = null

    fun capture() { source = last }
    fun clear() { last = null; source = null }

    fun frame(target: PhoneLyricsFadeFrame, changing: Boolean, progress: Float): PhoneLyricsFadeFrame {
        val p = progress.coerceIn(0f, 1f)
        val origin = source?.takeIf { changing }?.atPivot(target.pivot)
        fun mix(a: Float, b: Float) = a + (b - a) * p
        val result = when {
            !changing -> target
            origin == null -> target.copy(strength = target.strength * p)
            else -> target.copy(top = mix(origin.top, target.top), bottom = mix(origin.bottom, target.bottom),
                topHeight = mix(origin.topHeight, target.topHeight),
                bottomHeight = mix(origin.bottomHeight, target.bottomHeight),
                strength = mix(origin.strength, target.strength))
        }
        last = result
        return result
    }
}

@Composable
internal fun rememberPhoneLyricsFadeTurn(turn: PhonePlayerTurn?): PhoneLyricsFadeTurn? {
    if (turn == null) return null
    val state = remember(turn) { PhoneLyricsFadeTurn() }
    DisposableEffect(turn, state) {
        turn.beforeLayoutChange(state, state::capture)
        onDispose { turn.removeLayoutChangeListener(state) }
    }
    return state
}
