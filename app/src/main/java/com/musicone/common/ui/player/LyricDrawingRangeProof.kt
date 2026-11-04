package com.musicone

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/** 转向扩展区也必须完成文字放置及录制，不能只用焦点句的回执放行动画。 */
internal class LyricDrawingRangeProof(private val pane: LyricWindowPane, private val translated: List<Boolean>) {
    private val coordinates = mutableMapOf<Pair<Int, Boolean>, LayoutCoordinates>()
    private val drawn = mutableSetOf<Pair<Int, Boolean>>()

    fun placed(index: Int, translation: Boolean, value: LayoutCoordinates) {
        val key = index to translation
        if (coordinates[key] !== value) drawn.remove(key)
        coordinates[key] = value
    }
    fun drawn(index: Int, translation: Boolean, valid: Boolean) {
        val key = index to translation
        if (valid) drawn += key else drawn -= key
    }

    fun geometry(): Any? {
        val host = pane.listHost.coordinates() ?: return null
        if (!host.isAttached) return null
        val rows = pane.listState.layoutInfo.visibleItemsInfo
        if (rows.isEmpty()) return null
        return rows.map { row ->
            val parts = if (translated.getOrNull(row.index) == true) listOf(false, true) else listOf(false)
            row.index to parts.map { translation ->
                val key = row.index to translation
                val text = coordinates[key] ?: return null
                if (key !in drawn || !text.isAttached) return null
                val point = runCatching { host.localPositionOf(text, Offset.Zero) }.getOrNull() ?: return null
                LyricTextGeometry(IntOffset(point.x.roundToInt(), point.y.roundToInt()), text.size)
            }
        }
    }
}
