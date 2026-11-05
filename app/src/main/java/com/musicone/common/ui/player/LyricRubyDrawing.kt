package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// 汉字字面顶部相对基线的高度；注音紧贴字面，不受行高和字体内边距影响。
private const val LYRIC_RUBY_GLYPH_TOP_EM = .88f
// 罗马音音节种类很少，整首歌共用排版结果，只有第一次遇到时才测量。
private const val LYRIC_RUBY_CACHE_SIZE = 256

private class LyricRubyMeasurer(val resolver: FontFamily.Resolver, val density: Density,
    val direction: LayoutDirection) {
    val measurer = TextMeasurer(resolver, density, direction, LYRIC_RUBY_CACHE_SIZE)
}

private var sharedRubyMeasurer: LyricRubyMeasurer? = null

private fun lyricRubyMeasurer(resolver: FontFamily.Resolver, density: Density, direction: LayoutDirection): TextMeasurer {
    val shared = sharedRubyMeasurer
    if (shared != null && shared.resolver === resolver && shared.direction == direction && shared.density == density)
        return shared.measurer
    return LyricRubyMeasurer(resolver, density, direction).also { sharedRubyMeasurer = it }.measurer
}

private class LyricRubyGlyph(val image: ImageBitmap, val offset: IntOffset, val group: Int) {
    val left get() = offset.x.toFloat()
    val right get() = (offset.x + image.width).toFloat()
    val top get() = offset.y.toFloat()
    val bottom get() = (offset.y + image.height).toFloat()
    // 只有柔边正好经过的音才用到渐变，其余音不创建着色器。
    val light by lazy(LazyThreadSafetyMode.NONE) { LyricWordLightDrawing(image, Offset(left, top)) }
}

/** 字组在行内的横向范围，整组共用一道柔边。 */
private class LyricRubyExtent(val left: Float, val right: Float)

private class LyricRubyScene(
    val groups: List<LyricRubyGroup>,
    val extents: Array<LyricRubyExtent?>,
    val glyphs: List<LyricRubyGlyph>,
)

/** 注音画在正文同一绘制节点内，行级缩放、透明度、模糊和边界遮罩都自然跟随。 */
@Composable
internal fun Modifier.lyricRubyDrawing(
    groups: List<LyricRubyGroup>?,
    textLayout: () -> TextLayoutResult?,
    motion: LyricWordLightMotion,
    color: Color,
    typography: LyricTypography,
    fontMode: AppFontMode,
): Modifier {
    if (groups == null) return this
    val density = LocalDensity.current
    val resolver = LocalFontFamilyResolver.current
    val direction = LocalLayoutDirection.current
    val measurer = remember(resolver, density, direction) { lyricRubyMeasurer(resolver, density, direction) }
    val drawing = remember(groups, textLayout, motion, color, typography, fontMode, measurer) {
        Modifier.lyricDrawWithCache {
            val layout = textLayout()
            val scene = layout?.let {
                lyricRubyScene(groups, it, measurer, color, typography, fontMode, 1.dp.toPx(), size.width, this)
            }
            onDrawWithContent {
                drawContent()
                if (scene == null) return@onDrawWithContent
                val animated = motion.drawing
                val strength = motion.sweepStrength
                val spatial = motion.spatialLight
                val readable = motion.rangeCount
                var groupIndex = -1
                var groupProgress = 1f
                var extent: LyricRubyExtent? = null
                for (glyph in scene.glyphs) {
                    if (glyph.group != groupIndex) {
                        groupIndex = glyph.group
                        extent = scene.extents[groupIndex]
                        groupProgress = if (!animated) 1f else scene.groups[groupIndex].progress { range ->
                            if (range < readable) motion.sweepProgress(range) else 1f
                        }
                    }
                    val left = extent?.left ?: glyph.left
                    val right = extent?.right ?: glyph.right
                    val width = (right - left).coerceAtLeast(1f)
                    val first = if (!animated) 1f else lyricWordSweepAlpha(
                        groupProgress, (glyph.left - left) / width, strength, spatial)
                    val last = if (!animated) 1f else lyricWordSweepAlpha(
                        groupProgress, (glyph.right - left) / width, strength, spatial)
                    if (first == last) {
                        // 位图只在像素网格上定位，缩放由行图层统一采样，字形不会逐帧重新取整。
                        drawImage(glyph.image, dstOffset = glyph.offset, alpha = first, filterQuality = FilterQuality.High)
                    } else clipRect(glyph.left, glyph.top, glyph.right, glyph.bottom) {
                        glyph.light.draw(this, left, right, groupProgress, strength, 0f, 1f, spatial)
                    }
                }
            }
        }
    }
    return this.then(drawing)
}

private fun lyricRubyScene(
    groups: List<LyricRubyGroup>,
    layout: TextLayoutResult,
    measurer: TextMeasurer,
    color: Color,
    typography: LyricTypography,
    fontMode: AppFontMode,
    gapPx: Float,
    maxRight: Float,
    density: Density,
): LyricRubyScene? {
    val length = layout.layoutInput.text.length
    fun measure(text: String, fontSize: TextUnit): TextLayoutResult = measurer.measure(
        musicOneUiAnnotatedString(text, mode = fontMode),
        style = MusicOneTextStyles.lyricTranslation.copy(fontSize = fontSize, lineHeight = TextUnit.Unspecified),
        overflow = TextOverflow.Visible, softWrap = false, maxLines = 1,
    )
    val glyphs = ArrayList<LyricRubyGlyph>()
    val extents = arrayOfNulls<LyricRubyExtent>(groups.size)
    val base = typography.translationSp
    val glyphTop = with(density) { (typography.activeSp * LYRIC_RUBY_GLYPH_TOP_EM).sp.toPx() }
    val overhang = with(density) { (typography.activeSp * .35f).sp.toPx() }
    val indexed = groups.withIndex().filter { it.value.textStart < length }
    indexed.groupBy { layout.getLineForOffset(it.value.textStart) }.forEach { (visualLine, members) ->
        val spans = members.map { (_, group) ->
            val end = minOf(group.textEnd, length, layout.getLineEnd(visualLine, visibleEnd = true))
                .coerceAtLeast(group.textStart + 1)
            LyricRubySpan(layout.getHorizontalPosition(group.textStart, true),
                layout.getBoundingBox(end - 1).right,
                group.moras.sumOf { measure(it, base.sp).size.width }.toFloat())
        }
        val placements = placeLyricRubyLine(spans, maxRight, overhang, gapPx * 2f)
        val baseline = layout.getLineBaseline(visualLine)
        members.forEachIndexed { memberIndex, (groupIndex, group) ->
            val placement = placements[memberIndex]
            val fontSize = (base * placement.scale).sp
            var x = placement.left.roundToInt()
            group.moras.forEach { mora ->
                val measured = measure(mora, fontSize)
                val y = (baseline - glyphTop - gapPx - measured.size.height).roundToInt()
                val bounds = lyricRubyInkBounds(measured.size.width, measured.size.height,
                    with(density) { fontSize.toPx() })
                glyphs += LyricRubyGlyph(lyricRubyRaster(measured, bounds, color),
                    IntOffset(x + bounds.left, y + bounds.top), groupIndex)
                x += measured.size.width
            }
            extents[groupIndex] = LyricRubyExtent(placement.left.roundToInt().toFloat(), x.toFloat())
        }
    }
    return LyricRubyScene(groups, extents, glyphs).takeIf { glyphs.isNotEmpty() }
}
