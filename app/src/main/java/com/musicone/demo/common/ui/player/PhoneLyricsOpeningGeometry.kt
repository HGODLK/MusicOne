package com.musicone.demo

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/** 前沿坐标位于扩展后的列表内，内部对齐距离与窗口行程分开保存。 */
internal data class PhoneLyricsOpeningFront(val topPx: Float, val alignmentPx: Float)

/** 封面关闭态的实际边界；本次首开取一次快照，拖动和切歌不重算路径。 */
internal data class PhoneLyricsOpeningStage(
    val contentTopPx: Float,
    val informationBottomPx: Float,
    val informationTravelPx: Float,
    val travelPx: Float,
    val density: Float,
)

internal data class PhoneLyricsOpeningGeometry(
    val headerBegin: Float,
    val headerStartup: Float,
    val slowdown: Float,
)

internal fun phoneLyricsOpeningGeometry(stage: PhoneLyricsOpeningStage,
    front: PhoneLyricsOpeningFront): PhoneLyricsOpeningGeometry {
    val travel = (stage.travelPx + front.alignmentPx).coerceAtLeast(1f)
    val remaining = stage.contentTopPx + front.topPx - stage.informationBottomPx - 24f * stage.density
    // 除了起步空间，还保证接近安全边界时信息区至少已移动约 8dp。
    val visibleFraction = (8f * stage.density / stage.informationTravelPx.coerceAtLeast(1f)).coerceIn(0f, .25f)
    val visibleLead = 2f * visibleFraction * (travel - remaining).coerceAtLeast(0f) / (1f - visibleFraction)
    val lead = maxOf(48f * stage.density, visibleLead).coerceAtMost(remaining.coerceAtLeast(0f))
    val begin = ((remaining - lead) / travel).coerceIn(0f, .96f)
    val startup = (lead / travel).coerceIn(0f, 1f - begin)
    return PhoneLyricsOpeningGeometry(begin, startup, (remaining / travel).coerceIn(0f, .96f))
}

/** 复用行图层的缩放和位移计算，不把列表 offset 误当舞台坐标。 */
internal fun phoneLyricsOpeningFront(layout: LazyListLayoutInfo, context: LyricOpeningContext,
    current: Int, playback: LyricPlaybackStepMotion, density: Density,
    baseSize: Float, settle: Float): PhoneLyricsOpeningFront? {
    val first = layout.visibleItemsInfo.firstOrNull { it.index == context.first } ?: return null
    val row = playback.row(first.index)
    val largeText = layout.viewportSize.width >= with(density) { 400.dp.toPx() }
    val upcoming = if (largeText) 40f else MusicOneTextStyles.lyricUpcoming.fontSize.value
    val emphasis = upcoming / baseSize + (1f - upcoming / baseSize) * row.focus.value
    val scale = emphasis * if (first.index == current) .94f + .06f * settle.coerceIn(0f, 1f) else 1f
    val padding = with(density) { 5.dp.toPx() }
    val height = (first.size - 2f * padding).coerceAtLeast(0f)
    val top = first.offset - layout.viewportStartOffset + padding + height * (1f - scale) / 2f +
        playback.offsetPx(first.index) - with(density) { 6.dp.toPx() }
    return PhoneLyricsOpeningFront(top,
        layout.visibleItemsInfo.firstOrNull { it.index == current }?.offset?.toFloat() ?: context.focusOffsetPx)
}
