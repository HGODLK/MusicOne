package com.musicone

import android.graphics.Color as AndroidColor
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb

internal const val ATMOSPHERE_COLOR_DURATION_MS = 900f
internal const val ATMOSPHERE_SWEEP_STAGGER_MS = 137f
internal const val ATMOSPHERE_SWEEP_TOTAL_MS = ATMOSPHERE_COLOR_DURATION_MS + ATMOSPHERE_SWEEP_STAGGER_MS

private val AtmosphereSweepEasing = CubicBezierEasing(.2f, 0f, 0f, 1f)

/** 画布坐标换算为屏幕绝对视觉纵向位置：0 为上方，1 为下方。手机横屏时色场画布仍是竖屏窗口。 */
internal fun atmosphereVisualDepth(x: Float, y: Float, width: Float, height: Float, rotationDegrees: Int): Float =
    when {
        rotationDegrees > 0 -> 1f - x / width
        rotationDegrees < 0 -> x / width
        else -> y / height
    }.coerceIn(0f, 1f)

/** 视觉上方与下方在画布中的端点，底色渐变沿此方向铺开。 */
internal fun atmosphereVisualAxis(width: Float, height: Float, rotationDegrees: Int): Pair<Offset, Offset> = when {
    rotationDegrees > 0 -> Offset(width, 0f) to Offset.Zero
    rotationDegrees < 0 -> Offset.Zero to Offset(width, 0f)
    else -> Offset.Zero to Offset(0f, height)
}

/** 下一首下方先变、上方后变；上一首相反。返回该位置换色的缓动进度。 */
internal fun atmosphereSweepProgress(elapsedMs: Float, depth: Float, direction: TrackTransitionDirection): Float {
    val lead = if (direction == TrackTransitionDirection.NEXT) 1f - depth else depth
    val local = ((elapsedMs - lead * ATMOSPHERE_SWEEP_STAGGER_MS) / ATMOSPHERE_COLOR_DURATION_MS).coerceIn(0f, 1f)
    return AtmosphereSweepEasing.transform(local)
}

/** 一次换色扫动；起点按九个色团与底色两端分别记录，中途打断时从实际所见颜色续接。 */
internal class AtmosphereSweep(
    val fromColors: IntArray,
    val fromTop: Color,
    val fromBottom: Color,
    val toColors: IntArray,
    val direction: TrackTransitionDirection,
    settled: Boolean,
) {
    val toBase = averageArtworkColor(toColors)
    val elapsed = mutableFloatStateOf(if (settled) ATMOSPHERE_SWEEP_TOTAL_MS else 0f)
    // 绘制时回写最近一帧实际颜色，仅供打断续接读取，不参与重组。
    private val drawnColors = fromColors.copyOf()
    private var drawnTop = fromTop
    private var drawnBottom = fromBottom

    val settled get() = elapsed.floatValue >= ATMOSPHERE_SWEEP_TOTAL_MS

    fun colorAt(index: Int, progress: Float): Color {
        val color = lerp(Color(fromColors.colorAt(index)), Color(toColors.colorAt(index)), progress)
        if (index < drawnColors.size) drawnColors[index] = color.toArgb()
        return color
    }

    fun baseAt(top: Boolean, progress: Float): Color {
        val color = lerp(if (top) fromTop else fromBottom, toBase, progress)
        if (top) drawnTop = color else drawnBottom = color
        return color
    }

    /** 以当前所见颜色为起点衔接下一首。 */
    fun next(colors: IntArray, direction: TrackTransitionDirection, settled: Boolean): AtmosphereSweep =
        if (this.settled) AtmosphereSweep(toColors, toBase, toBase, colors, direction, settled)
        else AtmosphereSweep(drawnColors.copyOf(), drawnTop, drawnBottom, colors, direction, settled)

    companion object {
        fun still(colors: IntArray): AtmosphereSweep = averageArtworkColor(colors).let {
            AtmosphereSweep(colors, it, it, colors, TrackTransitionDirection.NEXT, settled = true)
        }
    }
}

private fun IntArray.colorAt(index: Int): Int = getOrElse(index) { lastOrNull() ?: AndroidColor.BLACK }

internal fun averageArtworkColor(colors: IntArray): Color {
    if (colors.isEmpty()) return Color.Black
    var red = 0L
    var green = 0L
    var blue = 0L
    colors.forEach { color ->
        red += AndroidColor.red(color)
        green += AndroidColor.green(color)
        blue += AndroidColor.blue(color)
    }
    return Color(
        red = red.toFloat() / colors.size / 255f,
        green = green.toFloat() / colors.size / 255f,
        blue = blue.toFloat() / colors.size / 255f,
        alpha = 1f,
    )
}
