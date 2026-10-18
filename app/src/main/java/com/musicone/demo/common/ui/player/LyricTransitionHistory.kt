package com.musicone.demo

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 历史上下文只用于屏外准备和行程测量，可见歌词始终由真实文字边界控制。 */
internal class LyricTransitionHistory {
    var preservingCloseWindow by mutableStateOf(false)
        private set
    var openingContext: LyricOpeningContext? = null
        private set

    fun prepare(layout: LazyListLayoutInfo, current: Int, density: Density,
        typography: LyricTypography? = null): Int {
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
        return lyricTransitionHistoryStart(current, previous?.size?.toFloat(),
            earlier?.size?.toFloat(), previous?.let { shortHeight(it.contentType == true) },
            earlier?.let { shortHeight(it.contentType == true) }, lyricDensity.density)
    }

    fun prepareOpening(context: LyricOpeningContext) {
        openingContext = context
    }

    fun releaseForSeek() {
        // seek 已改变入场目标，后续上下文由列表实测布局提供。
        openingContext = null
    }

    fun update(progress: Float, enabled: Boolean, closingWindow: Boolean = false) {
        if (closingWindow && progress > 0f) preservingCloseWindow = true
        if (progress <= 0f || progress >= 1f && !closingWindow) {
            preservingCloseWindow = false
        }
        // 端点仅释放准备快照，不再切换任何歌词行的绘制资格。
        if ((progress == 1f && !preservingCloseWindow) || !enabled) {
            openingContext = null
        }
    }

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
