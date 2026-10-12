package com.musicone.demo

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 选择完整的历史段，不改变列表布局、文字透明度或开合进度。 */
internal class LyricTransitionHistory {
    private var prepared = false
    private var firstRetained by mutableIntStateOf(0)
    private var limited by mutableStateOf(false)
    var preservingCloseWindow by mutableStateOf(false)
        private set
    val seekReveal = LyricSeekHistoryReveal()
    var openingContext: LyricOpeningContext? = null
        private set

    fun prepare(layout: LazyListLayoutInfo, current: Int, density: Density,
        typography: LyricTypography? = null): Int {
        seekReveal.clear()
        openingContext = null
        val previous = layout.visibleItemsInfo.firstOrNull { it.index == current - 1 }
        val earlier = layout.visibleItemsInfo.firstOrNull { it.index == current - 2 }
        val lyricDensity = lyricDisplayDensity(density)
        val measuredTypography = typography ?: lyricTypographyForWidth(
            if (layout.viewportSize.width >= with(lyricDensity) { 600.dp.toPx() })
                PlayerLayoutMode.WIDE_SINGLE else PlayerLayoutMode.COMPACT_SINGLE,
            layout.viewportSize.width / lyricDensity.density,
        )
        fun shortHeight(translated: Boolean) = with(lyricDensity) {
            measuredTypography.activeLineSp.sp.toPx() +
                10.dp.toPx() + if (translated) {
                    measuredTypography.translationLineSp.sp.toPx() + 2.dp.toPx()
                } else 0f
        }
        firstRetained = lyricTransitionHistoryStart(current, previous?.size?.toFloat(),
            earlier?.size?.toFloat(), previous?.let { shortHeight(it.contentType == true) },
            earlier?.let { shortHeight(it.contentType == true) }, lyricDensity.density)
        prepared = true
        return firstRetained
    }

    fun prepareOpening(context: LyricOpeningContext) {
        seekReveal.clear()
        openingContext = context
        firstRetained = context.first
        prepared = true
    }

    fun releaseForSeek(fadeAddedRows: Boolean = false) {
        if (limited && fadeAddedRows) seekReveal.revealBefore(firstRetained)
        // seek 已改变入场目标，后续可见上下文由列表实测布局提供，不能在端点补出旧限制外的行。
        openingContext = null
        firstRetained = 0
        limited = false
        prepared = true
    }

    fun update(layout: () -> LazyListLayoutInfo, current: Int, density: Density, progress: Float, enabled: Boolean,
        typography: LyricTypography? = null, closingWindow: Boolean = false) {
        if (closingWindow && progress > 0f) preservingCloseWindow = true
        if (progress <= 0f || progress >= 1f && !closingWindow) {
            preservingCloseWindow = false
        }
        limited = enabled && !preservingCloseWindow && progress > 0f && progress < 1f
        if (limited && !prepared) prepare(layout(), current, density, typography)
        // 中途反向沿用同一批完整段；到展开端点后恢复全部历史歌词。
        if ((progress == 1f && !preservingCloseWindow) || !enabled) {
            prepared = false
            openingContext = null
        }
    }

    fun draws(index: Int): Boolean = !limited || index >= firstRetained
}

internal fun lyricTransitionHistoryStart(current: Int, previous: Float?, earlier: Float?,
    previousShort: Float?, earlierShort: Float?, tolerance: Float): Int {
    val twoShort = previous != null && earlier != null && previousShort != null && earlierShort != null &&
        previous <= previousShort + tolerance && earlier <= earlierShort + tolerance
    return (current - if (twoShort) 2 else 1).coerceAtLeast(0)
}

/** 只在屏外准备：让第一段完整上下文从窗口起点进入，之后复用原有锚点交接。 */
internal suspend fun LazyListState.prepareTransitionHistory(history: LyricTransitionHistory,
    current: Int, density: Density, typography: LyricTypography? = null) {
    val first = history.prepare(layoutInfo, current, density, typography)
    scrollToItem(first, (-layoutInfo.viewportStartOffset).coerceAtLeast(0))
}
