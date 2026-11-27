package com.musicone

import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntRect
import kotlin.math.ceil
import kotlin.math.floor

/** 预备可见的后续句，复用正式绘制缓存；不持有可能已失效的图层或绘制作用域。 */
internal class LyricWordPreparation(val layout: TextLayoutResult, ranges: List<LyricHighlightRange>) {
    val parts by lazy(LazyThreadSafetyMode.NONE) { lyricWordRenderParts(layout, ranges) }
    var ready by mutableStateOf(false)
        private set

    suspend fun prepare(color: Color) {
        ready = false
        withFrameNanos { }
        val segments = parts.second
        if (!lyricWordInkIsolationRequired(layout.layoutInput.text.text)) {
            ready = true
            return
        }
        val overhang = lyricRasterOverhang(layout)
        // 每帧至多准备一个外伸词段；当前句需要时仍可立即走原来的按需路径。
        for (part in segments) {
            withFrameNanos { }
            val bounds = part.bounds.inflate(overhang)
            lyricTextRaster(layout, IntRect(floor(bounds.left).toInt(), floor(bounds.top).toInt(),
                ceil(bounds.right).toInt(), ceil(bounds.bottom).toInt()), color, listOf(part.textRange))
        }
        ready = true
    }
}

internal fun lyricRasterOverhang(layout: TextLayoutResult): Float {
    val text = layout.layoutInput.text.text
    val arabic = containsArabicOrPersian(text)
    val ink = if (arabic || lyricWordInkIsolationRequired(text))
        (0 until layout.lineCount).maxOfOrNull {
            (layout.getLineBottom(it) - layout.getLineTop(it)) * (if (arabic) .18f else .12f) + 1f
        } ?: 0f else 0f
    return ceil(maxOf(4f * layout.layoutInput.density.density, ink))
}
