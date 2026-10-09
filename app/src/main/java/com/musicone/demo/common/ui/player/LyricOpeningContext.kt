package com.musicone.demo

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/** 按最终绘制区域选择完整段落，包含顶部玻璃里部分可见的原文和翻译。 */
internal fun lyricOpeningHistoryStart(layout: LazyListLayoutInfo, current: Int,
    drawingTopPx: Float, blurMarginPx: Float): Int {
    val focus = requireNotNull(layout.visibleItemsInfo.firstOrNull { it.index == current })
    val top = layout.viewportStartOffset + drawingTopPx - blurMarginPx
    return layout.visibleItemsInfo.firstOrNull {
        it.index < current && it.offset - focus.offset + it.size > top
    }?.index ?: current
}

/** 新增上下文只影响准备范围；首开减速仍读取原来一两段的独立布局快照。 */
internal data class LyricOpeningContext(val first: Int, val compactLayout: LazyListLayoutInfo,
    val focusOffsetPx: Float = 0f)

internal suspend fun LazyListState.prepareOpeningContext(history: LyricTransitionHistory,
    current: Int, density: Density, drawingTopPx: Int) {
    val target = snapshotFlow { layoutInfo }.first {
        it.viewportSize.height > 0 && it.visibleItemsInfo.any { row -> row.index == current }
    }
    val first = lyricOpeningHistoryStart(target, current, drawingTopPx.toFloat(),
        with(density) { 6.dp.toPx() })
    val focusDistance = requireNotNull(target.visibleItemsInfo.firstOrNull { it.index == current }).offset -
        requireNotNull(target.visibleItemsInfo.firstOrNull { it.index == first }).offset
    revealTopLineForEntrance()
    prepareTransitionHistory(history, current, density)
    val compact = layoutInfo
    history.prepareOpening(LyricOpeningContext(first, compact))
    scrollToItem(first, (-layoutInfo.viewportStartOffset).coerceAtLeast(0))
    val prepared = layoutInfo
    val focusOffset = prepared.visibleItemsInfo.firstOrNull { it.index == current }?.offset
        ?: (focusDistance + (prepared.visibleItemsInfo.firstOrNull { it.index == first }?.offset ?: 0))
    history.prepareOpening(LyricOpeningContext(first, compact, focusOffset.toFloat()))
}
