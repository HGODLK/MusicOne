package com.musicone

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderNode
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.RequiresApi
import kotlin.math.ceil

/** 一次排版用到的全部外观；任何一项变化都重建当前句。 */
@RequiresApi(31)
internal data class DesktopLyricStyle(
    val density: Float,
    val typefaces: DesktopLyricTypefaces,
    val textDp: Int,
    val align: DesktopLyricsAlign,
    val highlight: Int,
    val translation: Boolean,
    val ruby: Boolean,
    val verbatim: Boolean,
    // 过长歌名是否来回滚动，减弱动态效果时关闭。
    val marquee: Boolean,
) {
    val textPx: Float get() = textDp * density
    val translationPx: Float get() = (textDp * .62f).coerceAtLeast(11f) * density
    val rubyPx: Float get() = textDp * .5f * density
    val strokePx: Float get() = 2.4f * density
    val inset: Float get() = 5f * density
    val rubyHeight: Float get() = if (ruby) rubyPx * 1.3f else 0f
    val mainHeight: Float get() = textPx * 1.42f
    val translationHeight: Float get() = if (translation) translationPx * 1.5f else 0f

    // 高度只由设置决定，有没有翻译都不改变窗口大小。
    val height: Float get() = rubyHeight + mainHeight + translationHeight
}

/** 单行文字的排版；阴影、描边和底色画一次即缓存，已唱部分每帧只换渐变位置。 */
@RequiresApi(31)
private class DesktopLyricRow(text: CharSequence, size: Float, private val strokePx: Float, typeface: android.graphics.Typeface) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = size
        this.typeface = typeface
        strokeJoin = Paint.Join.ROUND
    }
    private val layout: StaticLayout = StaticLayout.Builder.obtain(text, 0, text.length, paint,
        ceil(Layout.getDesiredWidth(text, paint)).toInt() + 1).setIncludePad(false).setMaxLines(1).build()
    val width: Float = layout.getLineWidth(0)
    val leftToRight: Boolean = layout.getParagraphDirection(0) == Layout.DIR_LEFT_TO_RIGHT
    private val baseline = layout.getLineBaseline(0)

    fun x(offset: Int): Float = layout.getPrimaryHorizontal(offset.coerceIn(0, layout.text.length))

    private inline fun at(canvas: Canvas, x: Float, baselineY: Float, block: () -> Unit) {
        canvas.save()
        canvas.translate(x, baselineY - baseline)
        block()
        canvas.restore()
    }

    fun drawBase(canvas: Canvas, x: Float, baselineY: Float, color: Int) = at(canvas, x, baselineY) {
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokePx
        paint.color = OUTLINE
        paint.setShadowLayer(strokePx * 1.6f, 0f, strokePx * .5f, SHADOW)
        layout.draw(canvas)
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
        paint.color = color
        layout.draw(canvas)
    }

    fun drawLit(canvas: Canvas, x: Float, baselineY: Float, color: Int, shader: Shader) = at(canvas, x, baselineY) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.shader = shader
        layout.draw(canvas)
        paint.shader = null
    }

    private companion object {
        const val OUTLINE = 0xA6000000.toInt()
        const val SHADOW = 0x80000000.toInt()
    }
}

/** 一整块歌词：注音、原文、翻译作为整体进出场。坐标原点在块的左上角。 */
@RequiresApi(31)
internal class DesktopLyricBlock(val content: DesktopLyricContent, private val style: DesktopLyricStyle, width: Float) {
    /** 文字排版与窗口宽度无关，改宽度只影响对齐、滚动和两侧渐隐。 */
    var width: Float = width

    private class Ruby(val row: DesktopLyricRow, val x: Float, val group: LyricRubyGroup)

    private val text = content.text.replace('\n', ' ').replace('\r', ' ')
    private val main = DesktopLyricRow(style.typefaces.styled(text), style.textPx, style.strokePx, style.typefaces.default)

    // 文字不变长度，逐字和注音的下标仍与原文一致。
    private val ranges = content.line?.takeIf { style.verbatim && it.words.isNotEmpty() && main.leftToRight }
        ?.let(::lyricWordHighlightRanges)?.takeIf { list -> list.any { it.word != null } }.orEmpty()
    private val rubies = buildRubies()
    private val translation = content.line?.translation?.trim()
        ?.takeIf { style.translation && it.isNotEmpty() && it != text.trim() }
        ?.let { DesktopLyricRow(style.typefaces.styled(it.replace('\n', ' ')), style.translationPx, style.strokePx * .8f, style.typefaces.default) }

    private val mainBaseline = style.rubyHeight + style.mainHeight * .74f
    private val translationBaseline = style.rubyHeight + style.mainHeight + style.translationHeight * .68f
    private val feather = 10f * style.density
    private val shaderMatrix = Matrix()
    private val lit = LinearGradient(0f, 0f, feather, 0f, style.highlight, style.highlight and 0x00FFFFFF, Shader.TileMode.CLAMP)
    private val fadePaint = Paint().apply { blendMode = BlendMode.DST_IN }
    private val mainNode = Cached("原文", main.width, style.rubyHeight + style.mainHeight) { canvas ->
        val base = when {
            content.key.kind != DesktopLyricKind.LINE -> PLAIN
            ranges.isEmpty() -> style.highlight
            // 高亮接近白色时压暗未唱部分，否则看不出逐字进度。
            HyperOsCardContrast.luminance(style.highlight) > .8 -> DIM_FOR_WHITE
            else -> PLAIN
        }
        rubies.forEach { it.row.drawBase(canvas, it.x, rubyBaseline, base) }
        main.drawBase(canvas, 0f, mainBaseline, base)
    }
    private val translationNode = translation?.let { row ->
        Cached("翻译", row.width, style.height) { row.drawBase(it, 0f, translationBaseline, PLAIN) }
    }

    private val rubyBaseline: Float get() = style.rubyHeight * .86f
    private val viewport: Float get() = width - style.inset * 2f

    private fun buildRubies(): List<Ruby> {
        val line = content.line ?: return emptyList()
        if (!style.ruby || line.transliteration.isNullOrBlank() || !main.leftToRight) return emptyList()
        val groups = lyricRubyGroups(line, ranges) ?: return emptyList()
        val shown = groups.filter { it.textStart < it.textEnd && it.textEnd <= text.length }
        fun row(group: LyricRubyGroup, scale: Float) = DesktopLyricRow(style.typefaces.styled(group.moras.joinToString("")),
            style.rubyPx * scale, style.strokePx * .7f, style.typefaces.default)
        val placements = placeLyricRubyLine(
            shown.map { LyricRubySpan(main.x(it.textStart), main.x(it.textEnd), row(it, 1f).width) },
            main.width, style.textPx * .35f, 2f * style.density)
        return shown.mapIndexed { index, group -> Ruby(row(group, placements[index].scale), placements[index].left, group) }
    }

    /** 窗口隐藏或节点离开画面后系统会丢弃绘制内容，下次绘制前按需重录。 */
    private inner class Cached(name: String, contentWidth: Float, contentHeight: Float, private val record: (Canvas) -> Unit) {
        private val nodeWidth = ceil(contentWidth + style.inset * 2f).toInt()
        private val nodeHeight = ceil(contentHeight + style.inset * 2f).toInt()
        private val node = RenderNode(name).apply {
            setPosition(0, 0, nodeWidth, nodeHeight)
            // 缓存成纹理后滚动和切句都不再重算阴影；过宽时交回普通绘制，避免超过纹理上限。
            setUseCompositingLayer(nodeWidth <= MAX_LAYER_WIDTH, null)
        }

        fun draw(canvas: Canvas) {
            if (!node.hasDisplayList()) {
                val recording = node.beginRecording(nodeWidth, nodeHeight)
                try {
                    recording.translate(style.inset, style.inset)
                    record(recording)
                } finally { node.endRecording() }
            }
            canvas.save()
            canvas.translate(-style.inset, -style.inset)
            canvas.drawRenderNode(node)
            canvas.restore()
        }

        fun release() = node.discardDisplayList()
    }

    private fun edge(wordPositionMs: Long): Float = desktopSungEdge(ranges, wordPositionMs, main::x)

    /** 推进期间是否需要逐帧重画；其余时间停帧等下一句。 */
    fun animating(positionMs: Long, wordPositionMs: Long): Boolean {
        val firstWord = ranges.firstOrNull { it.word != null }?.word
        val lastWord = ranges.lastOrNull { it.word != null }?.word
        val sweeping = firstWord != null && lastWord != null && wordPositionMs in firstWord.startMs..(lastWord.endMs + 50L)
        val scrolling = (ranges.isEmpty() && main.width > viewport || (translation?.width ?: 0f) > viewport) &&
            positionMs in content.startMs..content.scrollEndMs
        return sweeping || scrolling || titleScroll(positionMs)?.stillMs == 0L
    }

    private fun titleScroll(positionMs: Long): DesktopTitleScroll? =
        if (!style.marquee || content.key.kind != DesktopLyricKind.TITLE || main.width <= viewport) null
        else desktopTitleScroll(main.width - viewport, positionMs - content.startMs, style.density)

    /** 尚未开唱的逐字句要在首字到来时重新开始刷新；歌名停在两端时等到再次移动。 */
    fun nextStartMs(positionMs: Long, wordPositionMs: Long): Long? =
        titleScroll(positionMs)?.stillMs ?: ranges.firstOrNull { it.word != null }?.word?.startMs?.takeIf { it > wordPositionMs }?.minus(wordPositionMs)

    fun draw(canvas: Canvas, positionMs: Long, wordPositionMs: Long) {
        val edge = if (ranges.isEmpty()) null else edge(wordPositionMs)
        val scroll = titleScroll(positionMs)?.offset
            ?: desktopLyricScroll(main.width, viewport, edge, content.startMs, content.scrollEndMs, positionMs)
        drawRow(canvas, main.width, scroll, 0f, style.rubyHeight + style.mainHeight) {
            mainNode.draw(canvas)
            if (edge != null && edge > 0f) {
                main.drawLit(canvas, 0f, mainBaseline, style.highlight, litAt(edge, main.width))
                for (ruby in rubies) {
                    val progress = ruby.group.progress { index -> ranges.getOrNull(index)?.word?.let { desktopWordProgress(it, wordPositionMs) } ?: 1f }
                    if (progress > 0f) ruby.row.drawLit(canvas, ruby.x, rubyBaseline, style.highlight,
                        litAt(progress * ruby.row.width, ruby.row.width))
                }
            }
        }
        val row = translation ?: return
        val node = translationNode ?: return
        drawRow(canvas, row.width, desktopLyricScroll(row.width, viewport, null, content.startMs, content.scrollEndMs, positionMs),
            style.rubyHeight + style.mainHeight, style.height) { node.draw(canvas) }
    }

    /** 柔边从行首之外推进到行尾之外，起止两端都不会突然亮一截。 */
    private fun litAt(edge: Float, rowWidth: Float): Shader {
        val fraction = if (rowWidth <= 0f) 1f else (edge / rowWidth).coerceIn(0f, 1f)
        shaderMatrix.setTranslate(edge - feather + feather * fraction, 0f)
        lit.setLocalMatrix(shaderMatrix)
        return lit
    }

    private inline fun drawRow(canvas: Canvas, rowWidth: Float, scroll: Float, top: Float, bottom: Float, content: () -> Unit) {
        val overflow = rowWidth - viewport
        if (overflow <= 0f) {
            canvas.save()
            canvas.translate(style.align.rowStart(width, rowWidth, style.inset), 0f)
            content()
            canvas.restore()
            return
        }
        // 超长行先画到离屏层，再把有内容被遮住的一侧渐隐。
        val layer = canvas.saveLayer(0f, top - style.inset, width, bottom + style.inset, null)
        canvas.save()
        canvas.translate(style.inset - scroll, 0f)
        content()
        canvas.restore()
        val fade = 20f * style.density
        val left = 1f - (scroll / fade).coerceIn(0f, 1f)
        val right = 1f - ((overflow - scroll) / fade).coerceIn(0f, 1f)
        val stop = (fade / width).coerceIn(0f, .3f)
        fadePaint.shader = LinearGradient(0f, 0f, width, 0f,
            intArrayOf(alphaColor(left), Color.BLACK, Color.BLACK, alphaColor(right)),
            floatArrayOf(0f, stop, 1f - stop, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, top - style.inset, width, bottom + style.inset, fadePaint)
        canvas.restoreToCount(layer)
    }

    private fun alphaColor(alpha: Float): Int = Color.argb((alpha * 255).toInt().coerceIn(0, 255), 0, 0, 0)

    fun release() {
        mainNode.release()
        translationNode?.release()
    }

    private companion object {
        const val PLAIN = 0xFFF4F4F4.toInt()
        const val DIM_FOR_WHITE = 0xFFA8ACB2.toInt()
        const val MAX_LAYER_WIDTH = 4_096
    }
}
