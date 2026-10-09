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

    fun prepare(layout: LazyListLayoutInfo, current: Int, density: Density): Int {
        val previous = layout.visibleItemsInfo.firstOrNull { it.index == current - 1 }
        val earlier = layout.visibleItemsInfo.firstOrNull { it.index == current - 2 }
        val largeText = layout.viewportSize.width >= with(density) { 400.dp.toPx() }
        fun shortHeight(translated: Boolean) = with(density) {
            (if (largeText) 56.sp else MusicOneTextStyles.lyricActive.lineHeight).toPx() +
                10.dp.toPx() + if (translated) {
                    (if (largeText) 20.sp else MusicOneTextStyles.lyricTranslation.lineHeight).toPx() + 2.dp.toPx()
                } else 0f
        }
        firstRetained = lyricTransitionHistoryStart(current, previous?.size?.toFloat(),
            earlier?.size?.toFloat(), previous?.let { shortHeight(it.contentType == true) },
            earlier?.let { shortHeight(it.contentType == true) }, density.density)
        prepared = true
        return firstRetained
    }

    fun update(layout: () -> LazyListLayoutInfo, current: Int, density: Density, progress: Float, enabled: Boolean) {
        limited = enabled && progress > 0f && progress < 1f
        if (limited && !prepared) prepare(layout(), current, density)
        // 中途反向沿用同一批完整段；到展开端点后恢复全部历史歌词。
        if (progress == 1f || !enabled) prepared = false
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
    current: Int, density: Density) {
    val first = history.prepare(layoutInfo, current, density)
    scrollToItem(first, (-layoutInfo.viewportStartOffset).coerceAtLeast(0))
}
