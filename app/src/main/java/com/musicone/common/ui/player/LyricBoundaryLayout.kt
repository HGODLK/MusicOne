package com.musicone

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.derivedStateOf

/** 每次列表布局只建立一次位置表，正文、翻译和各行边界共享同一快照。 */
internal class LyricBoundaryLayout(list: LazyListState) {
    private val frame = derivedStateOf {
        val info = list.layoutInfo
        Frame(info.viewportSize.height.toFloat(), info.visibleItemsInfo.associate {
            it.index to (it.offset - info.viewportStartOffset).toFloat()
        })
    }

    fun top(index: Int): Float? = frame.value.tops[index]
    fun height(): Float = frame.value.height

    private data class Frame(val height: Float, val tops: Map<Int, Float>)
}
