package com.musicone

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntRect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

private data class LyricTextRasterKey(
    val layoutInput: TextLayoutInput,
    val bounds: IntRect,
    val color: Color,
    val visibleRanges: List<TextRange>?,
)

private class LyricTextRasterCache(private val maxRetainedBytes: Long) {
    private val entries = LinkedHashMap<LyricTextRasterKey, ImageBitmap>(8, .75f, true)
    private var retainedBytes = 0L

    @Synchronized
    fun getOrCreate(key: LyricTextRasterKey, draw: (ImageBitmap) -> Unit): ImageBitmap {
        entries[key]?.let { return it }
        val bitmap = ImageBitmap(key.bounds.width, key.bounds.height)
        playbackTrace("MusicOne:lyric-raster-miss") { draw(bitmap) }
        val bytes = key.bounds.width.toLong() * key.bounds.height * 4L
        if (bytes <= maxRetainedBytes) {
            entries[key] = bitmap
            retainedBytes += bytes
            val iterator = entries.entries.iterator()
            while (retainedBytes > maxRetainedBytes && iterator.hasNext()) {
                val eldest = iterator.next()
                retainedBytes -= eldest.key.bounds.width.toLong() * eldest.key.bounds.height * 4L
                iterator.remove()
            }
        }
        return bitmap
    }

    @Synchronized
    fun preparationCandidates(): List<ImageBitmap> {
        val recent = entries.values.toList().asReversed()
        return lyricRasterPreparationIndices(recent.map { it.width.toLong() * it.height * 4L }).map(recent::get)
    }
}

private val lyricTextRasterCache = LyricTextRasterCache(maxRetainedBytes = 8L * 1024L * 1024L)

// 注音按音节整首歌复用，单独限额，不挤占正文位图。
private val lyricRubyRasterCache = LyricTextRasterCache(maxRetainedBytes = 4L * 1024L * 1024L)

/** 仅向系统提交现有位图的准备请求，不录制或输出画面，也不宣称 GPU 已完成。 */
internal suspend fun prepareCachedLyricRasters() {
    for (raster in lyricTextRasterCache.preparationCandidates()) {
        currentCoroutineContext().ensureActive()
        try { raster.asAndroidBitmap().prepareToDraw() } catch (_: RuntimeException) { return }
        yield()
    }
}

/** 完整正文和外伸词段共用字节上限；播放时间与动画值不进入栅格缓存。 */
internal fun lyricTextRaster(
    layout: TextLayoutResult,
    bounds: IntRect,
    color: Color,
    visibleRanges: List<TextRange>? = null,
): ImageBitmap {
    val input = layout.layoutInput
    val key = LyricTextRasterKey(input, bounds, color, visibleRanges)
    return lyricTextRasterCache.getOrCreate(key) { raster ->
        val inkLayout = if (visibleRanges == null) layout else {
            // 只用透明颜色隐藏邻字，保留整句塑形上下文、字体和原排版宽度。
            TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection, cacheSize = 0).measure(
                text = lyricInkMaskedText(input.text, visibleRanges),
                style = input.style,
                overflow = input.overflow,
                softWrap = input.softWrap,
                maxLines = input.maxLines,
                placeholders = input.placeholders,
                constraints = input.constraints.copy(minWidth = layout.size.width, maxWidth = layout.size.width),
            )
        }
        drawLyricInk(raster, inkLayout, bounds, color)
    }
}

/** 单个音的位图；bounds 相对排版原点，明暗由绘制时的透明度决定，不进入缓存。 */
internal fun lyricRubyRaster(layout: TextLayoutResult, bounds: IntRect, color: Color): ImageBitmap =
    lyricRubyRasterCache.getOrCreate(LyricTextRasterKey(layout.layoutInput, bounds, color, null)) { raster ->
        drawLyricInk(raster, layout, bounds, color)
    }

private fun drawLyricInk(raster: ImageBitmap, layout: TextLayoutResult, bounds: IntRect, color: Color) {
    val input = layout.layoutInput
    CanvasDrawScope().draw(input.density, input.layoutDirection, Canvas(raster),
        Size(raster.width.toFloat(), raster.height.toFloat())) {
        translate(-bounds.left.toFloat(), -bounds.top.toFloat()) { drawText(layout, color = color) }
    }
}

/** 当前拉丁字体的 J/j 有向左外伸；整行隔离，避免钩部残留在邻词或基础层。 */
internal fun lyricWordInkIsolationRequired(text: CharSequence): Boolean = text.any { it == 'J' || it == 'j' }

internal fun lyricInkUncoveredRanges(textLength: Int, visibleRanges: List<TextRange>): List<TextRange> {
    val gaps = mutableListOf<TextRange>()
    var cursor = 0
    visibleRanges.sortedBy { it.min }.forEach { range ->
        val start = range.min.coerceIn(0, textLength)
        val end = range.max.coerceIn(start, textLength)
        if (start > cursor) gaps.add(TextRange(cursor, start))
        cursor = maxOf(cursor, end)
    }
    if (cursor < textLength) gaps.add(TextRange(cursor, textLength))
    return gaps
}

/** 不删除正文或字体标注；透明段仅改变绘制，不重新拼接词段文字。 */
internal fun lyricInkMaskedText(text: AnnotatedString, visibleRanges: List<TextRange>): AnnotatedString =
    AnnotatedString.Builder(text).apply {
        lyricInkUncoveredRanges(text.length, visibleRanges).forEach { range ->
            addStyle(SpanStyle(color = Color.Transparent), range.start, range.end)
        }
    }.toAnnotatedString()
