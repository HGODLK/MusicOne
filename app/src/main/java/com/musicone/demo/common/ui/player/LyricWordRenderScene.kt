package com.musicone.demo

import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Density
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private class LyricWordRenderPart(
    val rangeIndex: Int,
    val path: Path,
    val bounds: Rect,
    val baseline: Float,
)

private class LyricWordRenderNode(val rangeIndex: Int, val layer: GraphicsLayer)

private class DynamicLyricWordRenderScene(
    val layer: GraphicsLayer,
    private val words: List<LyricWordRenderNode>,
) {
    private var lastMotion: LyricWordLightMotion? = null
    private var lastRevision = 0

    fun update(motion: LyricWordLightMotion, revision: Int) {
        if (lastMotion === motion && lastRevision == revision) return
        words.forEach { word ->
            val scale = motion.scale(word.rangeIndex)
            word.layer.scaleX = scale
            word.layer.scaleY = scale
            word.layer.alpha = motion.alpha(word.rangeIndex)
        }
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

/** 字形和裁剪只录制一次；词段属性更新仍使用原逐字时钟。 */
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
            // 只有逐字明暗或缩放的行才创建词段节点；字形、基线和纹理仍完全共用。
            val prepareDynamic = {
                // 静止行不生成词段路径；首次进入动态时才准备，并随节点一并保留。
                val (coverage, parts) = lyricWordRenderParts(layout, ranges)
                val base = obtainGraphicsLayer()
                base.record(recordingDensity, recordingDirection, textSize) {
                    drawClippedLyricRaster(bitmap, origin, paint, coverage, ClipOp.Difference)
                }
                val nodes = parts.map { part ->
                    val left = floor(part.bounds.left).toInt()
                    val top = floor(part.bounds.top).toInt()
                    val width = (ceil(part.bounds.right).toInt() - left).coerceAtLeast(1)
                    val height = (ceil(part.bounds.bottom).toInt() - top).coerceAtLeast(1)
                    val word = obtainGraphicsLayer().apply {
                        topLeft = IntOffset(left, top)
                        pivotOffset = Offset(part.bounds.center.x.roundToInt().toFloat() - left,
                            part.baseline.roundToInt().toFloat() - top)
                        // 每个节点只有一次图像绘制，保持原画笔透明度语义，不增加词段离屏缓冲。
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    word.record(recordingDensity, recordingDirection, IntSize(width, height)) {
                        translate(left = -left.toFloat(), top = -top.toFloat()) {
                            drawClippedLyricRaster(bitmap, origin, paint, part.path)
                        }
                    }
                    LyricWordRenderNode(part.rangeIndex, word)
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
            (layout.getLineForOffset(start)..layout.getLineForOffset(end - 1)).mapNotNull { line ->
                val partStart = maxOf(start, layout.getLineStart(line))
                val partEnd = minOf(end, layout.getLineEnd(line))
                if (partStart >= partEnd) null else {
                    val path = layout.getPathForRange(partStart, partEnd)
                    val bounds = path.getBounds()
                    if (bounds.isEmpty) null else {
                        coverage.addPath(path)
                        LyricWordRenderPart(index, path, bounds, layout.getLineBaseline(line))
                    }
                }
            }
        }
    }
    return coverage to parts
}
