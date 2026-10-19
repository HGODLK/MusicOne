package com.musicone.demo

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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.Density
import kotlin.math.ceil
import kotlin.math.floor

private class LyricWordRenderPart(
    val rangeIndex: Int,
    val path: Path,
    val bounds: Rect,
    val textRange: TextRange,
    val lightStart: Float,
    val lightEnd: Float,
)

private class LyricWordRenderNode(
    val rangeIndex: Int,
    val layer: GraphicsLayer,
    private val lightStart: Float,
    private val lightEnd: Float,
    private val record: (Float, Float, Boolean) -> Unit,
) {
    private val lightSampler = LyricWordLightSampler(lowerEndpoint = 0f)

    fun update(motion: LyricWordLightMotion) {
        val progress = motion.sweepProgress(rangeIndex)
        val strength = motion.sweepStrength
        lightSampler.update(motion.lightTimeNanos, progress, strength, motion.immediateLight) { value, intensity ->
            val first = lyricWordSweepAlpha(value, lightStart, intensity, motion.spatialLight)
            val last = lyricWordSweepAlpha(value, lightEnd, intensity, motion.spatialLight)
            val uniform = first == last
            record(if (uniform) 1f else value, if (uniform) 0f else intensity, motion.spatialLight)
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

/** 保留原字形与裁剪；逐字渐亮最高 60fps，其他动效不受影响。 */
internal class LyricWordRenderScene private constructor(
    private val staticLayer: GraphicsLayer,
    private val prepareDynamic: () -> DynamicLyricWordRenderScene,
) {
    private var dynamic: DynamicLyricWordRenderScene? = null

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
                val (coverage, parts) = lyricWordRenderParts(layout, ranges)
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
                                light.draw(this, part.bounds.left - left, part.bounds.right - left, progress, strength, part.lightStart, part.lightEnd, spatial)
                            } else translate(left = -left.toFloat(), top = -top.toFloat()) {
                                clipPath(part.path) {
                                    translate(left = left.toFloat(), top = top.toFloat()) {
                                        light.draw(this, part.bounds.left - left, part.bounds.right - left, progress, strength, part.lightStart, part.lightEnd, spatial)
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

private fun lyricWordRenderParts(layout: TextLayoutResult,
    ranges: List<LyricHighlightRange>): Pair<Path, List<LyricWordRenderPart>> {
    val textLength = layout.layoutInput.text.length
    val coverage = Path()
    val parts = ranges.flatMapIndexed { index, range ->
        val start = range.start.coerceIn(0, textLength)
        val end = range.end.coerceIn(start, textLength)
        if (start >= end) emptyList() else {
            val segments = (layout.getLineForOffset(start)..layout.getLineForOffset(end - 1)).mapNotNull { line ->
                val partStart = maxOf(start, layout.getLineStart(line))
                val partEnd = minOf(end, layout.getLineEnd(line))
                if (partStart >= partEnd) null else {
                    val path = layout.getPathForRange(partStart, partEnd)
                    val bounds = path.getBounds()
                    if (bounds.isEmpty) null else {
                        coverage.addPath(path)
                        LyricWordRenderPart(index, path, bounds, TextRange(partStart, partEnd), 0f, 1f)
                    }
                }
            }
            // 跨行词段延续同一提亮进度，第二行不从词首重新开始。
            val totalWidth = segments.sumOf { it.bounds.width.toDouble() }.toFloat().coerceAtLeast(1f)
            var offset = 0f
            segments.map { part ->
                val lightStart = offset / totalWidth
                offset += part.bounds.width
                LyricWordRenderPart(part.rangeIndex, part.path, part.bounds,
                    part.textRange, lightStart, offset / totalWidth)
            }
        }
    }
    return coverage to parts
}
