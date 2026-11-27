package com.musicone

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.IntSize

/** 判断歌词和页眉是否都已完全关闭静止，可以进入待命状态。 */
internal fun phoneLyricsStandby(visible: Boolean, lyrics: Boolean, progress: Float,
    header: Float, dragging: Boolean): Boolean =
    visible && !lyrics && progress == 0f && header == 0f && !dragging

/** 缓存屏外准备好的布局快照，下次展开若未变化可直接复用，避免重复跳转。 */
internal class LyricOpeningStandby {
    private var prepared: Layout? = null
    private var context: LyricOpeningContext? = null

    fun invalidate() { prepared = null; context = null }

    fun record(current: Int, layout: LazyListLayoutInfo, context: LyricOpeningContext?) {
        prepared = Layout(current, layout)
        this.context = context
    }

    fun matches(current: Int, layout: LazyListLayoutInfo, context: LyricOpeningContext?): Boolean =
        prepared != null && this.context === context && prepared == Layout(current, layout)

    private data class Layout(val current: Int, val size: IntSize, val start: Int, val end: Int,
        val count: Int, val spacing: Int, val rows: List<Triple<Int, Int, Int>>) {
        constructor(current: Int, layout: LazyListLayoutInfo) : this(current, layout.viewportSize,
            layout.viewportStartOffset, layout.viewportEndOffset, layout.totalItemsCount,
            layout.mainAxisItemSpacing, layout.visibleItemsInfo.map { Triple(it.index, it.offset, it.size) })
    }
}
