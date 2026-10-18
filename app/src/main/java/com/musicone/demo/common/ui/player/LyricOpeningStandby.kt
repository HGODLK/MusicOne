package com.musicone.demo

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.IntSize

/** 仅在两个窗口都完全关闭后待命，收尾和反向途中保持原来的坐标。 */
internal fun phoneLyricsStandby(visible: Boolean, lyrics: Boolean, progress: Float,
    header: Float, dragging: Boolean): Boolean =
    visible && !lyrics && progress == 0f && header == 0f && !dragging

/** 保存实测准备结果，打开时不再先跳回焦点、再跳到历史段。 */
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
