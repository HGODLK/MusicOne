package com.musicone.demo

import android.util.LruCache
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

private data class FeedTextLayoutKey(
    val text: AnnotatedString,
    val width: Int,
    val style: TextStyle,
    val density: Float,
    val fontScale: Float,
    val direction: LayoutDirection,
    val resolver: FontFamily.Resolver,
    val preferredLines: Int,
    val maximum: Int,
    val minimum: Int,
)

/** 卡片离开视口后仍保留适配字号；颜色变化不使排版缓存失效。 */
internal object QqFeedTextLayoutCache {
    private val sizes = LruCache<FeedTextLayoutKey, Int>(256)

    fun fontSize(text: AnnotatedString, width: Int, style: TextStyle, density: Density,
        direction: LayoutDirection, resolver: FontFamily.Resolver, preferredLines: Int,
        maximum: Int, minimum: Int, measurer: TextMeasurer): Int {
        val key = FeedTextLayoutKey(text, width, style, density.density, density.fontScale,
            direction, resolver, preferredLines, maximum, minimum)
        synchronized(sizes) { sizes.get(key) }?.let { return it }
        val result = (maximum downTo minimum).firstOrNull { size ->
            measurer.measure(
                text, style.copy(fontSize = size.sp, lineHeight = (size * 1.4f).sp),
                // 只需判断是否超过目标行数，长文案不必在每次尝试时排完全部行。
                maxLines = preferredLines + 1,
                constraints = Constraints(maxWidth = width),
            ).lineCount <= preferredLines
        } ?: minimum
        // 锁只保护缓存读写，后台试排不能持锁阻塞主线程。
        synchronized(sizes) { sizes.put(key, result) }
        return result
    }
}
