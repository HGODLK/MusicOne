package com.musicone

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 竖屏和平板底部控件的尺寸；手机横屏有独立布局，不使用这里的数值。 */
internal data class PlayerControlsMetrics(
    val timeline: Dp,
    val timeLabelsLift: Dp,
    val row: Dp,
    val tabletRow: Dp,
    val playButton: Dp,
    val playIcon: Dp,
    val trackButton: Dp,
    val trackIcon: Dp,
    val sideButton: Dp,
    val bottomRow: Dp,
    val estimatedHeight: Dp,
) {
    companion object {
        val Normal = PlayerControlsMetrics(64.dp, 0.dp, 100.dp, 80.dp, 70.dp, 54.dp, 56.dp, 38.dp, 48.dp, 58.dp, 238.dp)

        // 底部行少留的 8dp 只占用控制行下方的空白，音质和播放模式按钮碰不到歌词和播放列表按钮。
        val Compact = PlayerControlsMetrics(44.dp, 8.dp, 64.dp, 56.dp, 56.dp, 42.dp, 44.dp, 30.dp, 44.dp, 50.dp, 166.dp)
    }
}

internal fun playerControlsMetrics(compact: Boolean = ExperiencePreferences.options.compactPlayerControls) =
    if (compact) PlayerControlsMetrics.Compact else PlayerControlsMetrics.Normal

/** 播放页重建后沿用量过的控件高度；文字位置和飞行落点都依赖它，估算不准时文字会先偏低再跳上去。 */
internal object PlayerControlsHeightMemory {
    private data class Key(val density: Float, val fontScale: Float, val compact: Boolean)
    private val heights = HashMap<Key, Int>()

    fun read(density: Density, compact: Boolean): Int? = heights[Key(density.density, density.fontScale, compact)]

    fun write(density: Density, compact: Boolean, heightPx: Int) {
        if (heightPx > 0) heights[Key(density.density, density.fontScale, compact)] = heightPx
    }
}

/** 时间文字贴近进度条，同时扣掉占位高度，整组控件才真的变矮。 */
internal fun Modifier.playerTimeLabelsLift(lift: Dp): Modifier = if (lift == 0.dp) this else layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val liftPx = lift.roundToPx()
    layout(placeable.width, (placeable.height - liftPx).coerceAtLeast(0)) { placeable.place(0, -liftPx) }
}
