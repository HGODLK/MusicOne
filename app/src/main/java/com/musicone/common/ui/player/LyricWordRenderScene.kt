package com.musicone

import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.Density
import kotlin.math.ceil
import kotlin.math.floor

internal class LyricWordRenderPart(
    val rangeIndex: Int,
    val path: Path,
    val bounds: Rect,
    val textRange: TextRange,
    val lightStart: Float,
    val lightEnd: Float,
    val rtl: Boolean = false,
    val lightLeft: Float = bounds.left,
    val lightRight: Float = bounds.right,
)

private class LyricWordRenderNode(
    val rangeIndex: Int,
    val layer: GraphicsLayer,
    private val lightStart: Float,
    private val lightEnd: Float,
    private val record: (Float, Float, Boolean) -> Unit,
) {
    private val lightSampler = LyricWordLightSampler(lowerEndpoint = 0f)
    private var uniformRecording = true

    fun update(motion: LyricWordLightMotion) {
        val progress = motion.sweepProgress(rangeIndex)
        val strength = motion.sweepStrength
        lightSampler.update(motion.lightTimeNanos, progress, strength, motion.immediateLight) { value, intensity ->
            val first = lyricWordSweepAlpha(value, lightStart, intensity, motion.spatialLight)
            val last = lyricWordSweepAlpha(value, lightEnd, intensity, motion.spatialLight)
            val uniform = first == last
            // 均匀亮度只改图层透明度；仅从柔边回到均匀时恢复一次原字形录制。
            if (!uniform || !uniformRecording) {
                record(if (uniform) 1f else value, if (uniform) 0f else intensity, motion.spatialLight)
            }
            uniformRecording = uniform
            layer.alpha = if (uniform) first else 1f
        }?.let(motion::scheduleLightSample)
    }
}

private class DynamicLyricWordRenderScene(
    val layer: GraphicsLayer,
    private val words: List<LyricWordRenderNode>,
) {
    private var lastMotion: LyricWordLightMotion? = null
    private var lastRevision = 0

    fun update(motion: LyricWordLightMotion, revision: Int) {
        if (lastMotion === motion && lastRevision == revision) return
        words.forEach { it.update(motion) }
        lastMotion = motion
        lastRevision = revision
    }
}

private fun DrawScope.drawClippedLyricRaster(
    bitmap: ImageBitmap,
    origin: Offset,
    paint: Paint,
    path: Path,
    clipOp: ClipOp = ClipOp.Intersect,
) {
    drawIntoCanvas { canvas ->
        canvas.save()
        canvas.clipPath(path, clipOp)
        canvas.drawImage(bitmap, origin, paint)
        canvas.restore()
    }
}

/** 逐字歌词绘制场景：缓存整句和各词段图层，逐字渐亮限 60fps，不影响其他动效帧率。 */
internal class LyricWordRenderScene private constructor(
    private val staticLayer: GraphicsLayer,
    private val prepareDynamic: () -> DynamicLyricWordRenderScene,
) {
    private var dynamic: DynamicLyricWordRenderScene? = null

    fun prepareAhead() {
        // 后续句仍提前准备，但只在一个有效绘制事务内发布完整场景。
        if (dynamic == null) dynamic = playbackTrace("MusicOne:lyric-words-prewarm") { prepareDynamic() }
    }

    fun layerFor(motion: LyricWordLightMotion): GraphicsLayer {
        val revision = motion.renderRevision
        if (!motion.drawing) return staticLayer
        val animated = dynamic ?: playbackTrace("MusicOne:lyric-words-prepare") {
            prepareDynamic()
        }.also { dynamic = it }
        animated.update(motion, revision)
        return animated.layer
    }

    companion object {
        fun create(
            scope: CacheDrawScope,
            layout: TextLayoutResult,
            ranges: List<LyricHighlightRange>,
            bitmap: ImageBitmap,
            overhang: Float,
            color: Color,
            isolateInk: Boolean,
            preparation: LyricWordPreparation? = null,
        ): LyricWordRenderScene? = with(scope) {
            if (layout.layoutInput.text.isEmpty()) return@with null
            val paint = Paint().apply { filterQuality = FilterQuality.High }
            val origin = Offset(-overhang, -overhang)
            // 延迟录制显式使用布局时的密度、方向和尺寸，不依赖缓存保存的绘制作用域。
            val recordingDensity = Density(density, fontScale)
            val recordingDirection = layoutDirection
            val textSize = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())
            val staticLayer = obtainGraphicsLayer()
            staticLayer.record(recordingDensity, recordingDirection, textSize) {
                // 没有词级变换时直接重放完整正文，避免每个词都裁剪并采样同一张纹理。
                drawIntoCanvas { it.drawImage(bitmap, origin, paint) }
            }
            // 只有逐字明暗的行才创建词段节点，保持实际排版位置。
            val prepareDynamic = {
                // 静止行不生成词段路径；首次进入动态时才准备，并随节点一并保留。
                val (coverage, parts) = preparation?.parts ?: lyricWordRenderParts(layout, ranges)
                val base = obtainGraphicsLayer()
                val uncovered = if (isolateInk) lyricInkUncoveredRanges(layout.layoutInput.text.length,
                    parts.map { it.textRange }) else emptyList()
                val baseInk = if (uncovered.isNotEmpty()) lyricTextRaster(layout,
                    IntRect(-overhang.toInt(), -overhang.toInt(), bitmap.width - overhang.toInt(),
                        bitmap.height - overhang.toInt()), color, uncovered) else null
                base.record(recordingDensity, recordingDirection, textSize) {
                    if (!isolateInk) drawClippedLyricRaster(bitmap, origin, paint, coverage, ClipOp.Difference)
                    else if (baseInk != null) drawIntoCanvas { it.drawImage(baseInk, origin, paint) }
                }
                val nodes = parts.map { part ->
                    val rasterBounds = if (isolateInk) part.bounds.inflate(overhang) else part.bounds
                    val left = floor(rasterBounds.left).toInt()
                    val top = floor(rasterBounds.top).toInt()
                    val width = (ceil(rasterBounds.right).toInt() - left).coerceAtLeast(1)
                    val height = (ceil(rasterBounds.bottom).toInt() - top).coerceAtLeast(1)
                    // 隐藏邻字后保留完整词段笔画，外伸钩部不再被选区裁剪或重复绘制。
                    val wordInk = if (isolateInk) lyricTextRaster(layout,
                        IntRect(left, top, left + width, top + height), color, listOf(part.textRange)) else null
                    val word = obtainGraphicsLayer().apply {
                        topLeft = IntOffset(left, top)
                        // 每个节点只有一次图像绘制，保持原画笔透明度语义，不增加词段离屏缓冲。
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    val light = LyricWordLightDrawing(wordInk ?: bitmap,
                        if (wordInk != null) Offset.Zero else Offset(origin.x - left, origin.y - top))
                    val recordWord: (Float, Float, Boolean) -> Unit = { progress, strength, spatial ->
                        word.record(recordingDensity, recordingDirection, IntSize(width, height)) {
                            if (strength == 0f) {
                                if (wordInk != null) drawIntoCanvas { it.drawImage(wordInk, Offset.Zero, paint) }
                                else translate(left = -left.toFloat(), top = -top.toFloat()) {
                                    drawClippedLyricRaster(bitmap, origin, paint, part.path)
                                }
                            } else if (wordInk != null) {
                                light.draw(this, part.lightLeft - left, part.lightRight - left, progress, strength, part.lightStart, part.lightEnd, spatial, part.rtl)
                            } else translate(left = -left.toFloat(), top = -top.toFloat()) {
                                clipPath(part.path) {
                                    translate(left = left.toFloat(), top = top.toFloat()) {
                                        light.draw(this, part.lightLeft - left, part.lightRight - left, progress, strength, part.lightStart, part.lightEnd, spatial, part.rtl)
                                    }
                                }
                            }
                        }
                    }
                    recordWord(1f, 0f, true)
                    LyricWordRenderNode(part.rangeIndex, word, part.lightStart, part.lightEnd, recordWord)
                }
                val scene = obtainGraphicsLayer()
                scene.record(recordingDensity, recordingDirection, textSize) {
                    drawLayer(base)
                    nodes.forEach { drawLayer(it.layer) }
                }
                DynamicLyricWordRenderScene(scene, nodes)
            }
            // 所有节点由绘制缓存持有，布局失效或行退出时交回 Compose 的图层池。
            LyricWordRenderScene(staticLayer, prepareDynamic)
        }
    }
}

private class RawLyricWordSegment(
    val rangeIndex: Int,
    val line: Int,
    val rawPath: Path,
    val rawBounds: Rect,
    val textRange: TextRange,
    val rtl: Boolean,
)

internal fun lyricWordRenderParts(layout: TextLayoutResult,
    ranges: List<LyricHighlightRange>): Pair<Path, List<LyricWordRenderPart>> {
    val text = layout.layoutInput.text.text
    val textLength = text.length
    val hasArabic = containsArabicOrPersian(text)
    val overhang = if (hasArabic) lyricRasterOverhang(layout) else 0f
    val rawSegments = ArrayList<RawLyricWordSegment>(ranges.size)
    ranges.forEachIndexed { index, range ->
        val start = range.start.coerceIn(0, textLength)
        val end = range.end.coerceIn(start, textLength)
        if (start >= end) return@forEachIndexed
        for (line in layout.getLineForOffset(start)..layout.getLineForOffset(end - 1)) {
            val partStart = maxOf(start, layout.getLineStart(line))
            val partEnd = minOf(end, layout.getLineEnd(line))
            if (partStart >= partEnd) continue
            // 若单个词段跨越双向边界（如波斯词紧贴英文），拆为连续同向子段分别扫光。
            for ((runStart, runEnd, isRtl) in lyricBidiSubRanges(layout, partStart, partEnd)) {
                val rawBounds = lyricLineRangeBounds(layout, line, runStart, runEnd)
                if (!rawBounds.isEmpty) {
                    val rawPath = Path().apply { addRect(rawBounds, Path.Direction.Clockwise) }
                    rawSegments.add(RawLyricWordSegment(index, line, rawPath, rawBounds, TextRange(runStart, runEnd), isRtl))
                }
            }
        }
    }
    // 含阿拉伯／波斯文时，每行仅对视觉最左与最右且贴近行端的唯一词段外扩水平裁剪框，避免与邻词重叠。
    val leftmostPerLine = IntArray(layout.lineCount) { -1 }
    val rightmostPerLine = IntArray(layout.lineCount) { -1 }
    if (hasArabic && overhang > 0f) {
        rawSegments.forEachIndexed { segIndex, seg ->
            val line = seg.line
            val curLeft = leftmostPerLine[line]
            if (curLeft < 0 || seg.rawBounds.left < rawSegments[curLeft].rawBounds.left) leftmostPerLine[line] = segIndex
            val curRight = rightmostPerLine[line]
            if (curRight < 0 || seg.rawBounds.right > rawSegments[curRight].rawBounds.right) rightmostPerLine[line] = segIndex
        }
    }
    val coverage = Path()
    val rawPartsByRange = HashMap<Int, MutableList<LyricWordRenderPart>>(ranges.size)
    rawSegments.forEachIndexed { segIndex, seg ->
        val line = seg.line
        val expandLeft = hasArabic && overhang > 0f && leftmostPerLine[line] == segIndex &&
            seg.rawBounds.left <= layout.getLineLeft(line) + 1f
        val expandRight = hasArabic && overhang > 0f && rightmostPerLine[line] == segIndex &&
            seg.rawBounds.right >= layout.getLineRight(line) - 1f
        val finalBounds = lyricWordClipBounds(
            rawBounds = seg.rawBounds,
            expandLeft = expandLeft,
            expandTop = hasArabic && line == 0,
            expandRight = expandRight,
            expandBottom = hasArabic && line == layout.lineCount - 1,
            overhang = overhang,
        )
        val finalPath = lyricWordClipPath(seg.rawPath, seg.rawBounds, finalBounds)
        coverage.addPath(finalPath)
        rawPartsByRange.getOrPut(seg.rangeIndex) { ArrayList(1) }.add(
            LyricWordRenderPart(seg.rangeIndex, finalPath, finalBounds, seg.textRange, 0f, 1f, seg.rtl,
                seg.rawBounds.left, seg.rawBounds.right),
        )
    }
    val parts = ranges.indices.flatMap { rangeIndex ->
        val segments = rawPartsByRange[rangeIndex] ?: return@flatMap emptyList()
        // 跨行／跨向词段按原字形宽度延续同一提亮进度，不被裁剪留白拉偏。
        val totalWidth = segments.sumOf { (it.lightRight - it.lightLeft).coerceAtLeast(0f).toDouble() }
            .toFloat().coerceAtLeast(1f)
        var offset = 0f
        segments.map { part ->
            val lightStart = offset / totalWidth
            offset += (part.lightRight - part.lightLeft).coerceAtLeast(0f)
            LyricWordRenderPart(part.rangeIndex, part.path, part.bounds,
                part.textRange, lightStart, offset / totalWidth, part.rtl, part.lightLeft, part.lightRight)
        }
    }
    return coverage to parts
}

/**
 * 计算同一视觉行内 [start, end) 字符范围的真实包围盒。
 * 不使用 TextLayoutResult.getPathForRange：Android StaticLayout.getSelectionPath 在 end 触及软折行
 * 行尾时会把下一行行首（x=0）的退化边并入 Path，导致行尾词与注音的 bounds.left 坍缩为 0。
 */
internal fun lyricLineRangeBounds(layout: TextLayoutResult, line: Int, start: Int, end: Int): Rect {
    val text = layout.layoutInput.text.text
    val safeStart = start.coerceIn(0, text.length)
    val safeEnd = end.coerceIn(safeStart, text.length)
    if (safeStart >= safeEnd) return Rect.Zero
    return lyricLineHorizontalBounds(safeStart, safeEnd, text,
        lineTop = layout.getLineTop(line), lineBottom = layout.getLineBottom(line)) { offset ->
        layout.getBoundingBox(offset)
    }
}

internal fun lyricLineHorizontalBounds(
    start: Int,
    end: Int,
    text: CharSequence,
    lineTop: Float,
    lineBottom: Float,
    boundingBox: (Int) -> Rect,
): Rect {
    if (start >= end || lineBottom <= lineTop) return Rect.Zero
    var left = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var anyLeft = Float.POSITIVE_INFINITY
    var anyRight = Float.NEGATIVE_INFINITY
    var offset = start
    while (offset < end) {
        val ch = text[offset]
        if (ch == '\n' || ch == '\r') {
            offset++
            continue
        }
        val box = boundingBox(offset)
        val boxLeft = minOf(box.left, box.right)
        val boxRight = maxOf(box.left, box.right)
        if (boxLeft.isFinite() && boxRight.isFinite()) {
            anyLeft = minOf(anyLeft, boxLeft)
            anyRight = maxOf(anyRight, boxRight)
            if (boxRight > boxLeft) {
                left = minOf(left, boxLeft)
                right = maxOf(right, boxRight)
            }
        }
        offset += if (Character.isHighSurrogate(ch) && offset + 1 < end && Character.isLowSurrogate(text[offset + 1])) 2 else 1
    }
    val finalLeft = if (left.isFinite()) left else anyLeft
    val finalRight = if (right.isFinite()) right else anyRight
    if (!finalLeft.isFinite() || !finalRight.isFinite() || finalRight <= finalLeft) return Rect.Zero
    return Rect(finalLeft, lineTop, finalRight, lineBottom)
}

/** 计算阿拉伯／波斯歌词词段的外扩裁剪框；只在外侧扩充，不侵入邻词范围。 */
internal fun lyricWordClipBounds(
    rawBounds: Rect,
    expandLeft: Boolean,
    expandTop: Boolean,
    expandRight: Boolean,
    expandBottom: Boolean,
    overhang: Float,
): Rect {
    if (overhang <= 0f) return rawBounds
    val left = if (expandLeft) rawBounds.left - overhang else rawBounds.left
    val top = if (expandTop) rawBounds.top - overhang else rawBounds.top
    val right = if (expandRight) rawBounds.right + overhang else rawBounds.right
    val bottom = if (expandBottom) rawBounds.bottom + overhang else rawBounds.bottom
    return if (left == rawBounds.left && top == rawBounds.top && right == rawBounds.right && bottom == rawBounds.bottom) {
        rawBounds
    } else {
        Rect(left, top, right, bottom)
    }
}

/**
 * 构建词段裁剪路径。
 * 扩充裁剪框时仅添加单一顺时针矩形，与 Android `Layout.getSelectionPath`（CW）绕向一致；
 * 不能在 `NonZero` 路径内叠加默认逆时针的 `addRect` 与原 `rawPath`，否则正文区域会绕数抵消被镂空。
 */
internal fun lyricWordClipPath(
    rawPath: Path,
    rawBounds: Rect,
    clipBounds: Rect,
    createPath: () -> Path = ::Path,
): Path = if (clipBounds == rawBounds) rawPath else createPath().apply {
    addRect(clipBounds, Path.Direction.Clockwise)
}

private data class LyricBidiSubRange(val start: Int, val end: Int, val rtl: Boolean)

private fun lyricBidiSubRanges(layout: TextLayoutResult, start: Int, end: Int): List<LyricBidiSubRange> {
    if (start >= end) return emptyList()
    val result = ArrayList<LyricBidiSubRange>(1)
    var runStart = start
    var currentRtl = layout.getBidiRunDirection(start) == ResolvedTextDirection.Rtl
    for (offset in (start + 1) until end) {
        val nextRtl = layout.getBidiRunDirection(offset) == ResolvedTextDirection.Rtl
        if (nextRtl != currentRtl) {
            result.add(LyricBidiSubRange(runStart, offset, currentRtl))
            runStart = offset
            currentRtl = nextRtl
        }
    }
    result.add(LyricBidiSubRange(runStart, end, currentRtl))
    return result
}
