package com.musicone.demo

import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.floor

/** 裁剪指令只准备一次；整条文字带共同渐显，保留逐字重叠时的合成透明度。 */
internal class LyricBoundaryRenderScene(
    scope: CacheDrawScope,
    source: GraphicsLayer,
    bands: List<Pair<Float, Float>>,
    overhang: Float,
) {
    private val layers: List<GraphicsLayer>
    private val composite = scope.obtainGraphicsLayer()

    init {
        val density = Density(scope.density, scope.fontScale)
        val direction = scope.layoutDirection
        val width = scope.size.width
        val height = scope.size.height
        layers = bands.mapIndexed { index, (lineTop, lineBottom) ->
            val bandTop = if (index == 0) -overhang else lineTop
            val bandBottom = if (index == bands.lastIndex) height + overhang else lineBottom
            val left = floor(-overhang).toInt()
            val top = floor(bandTop).toInt()
            val bandSize = IntSize(
                (ceil(width + overhang).toInt() - left).coerceAtLeast(1),
                (ceil(bandBottom).toInt() - top).coerceAtLeast(1),
            )
            scope.obtainGraphicsLayer().apply {
                topLeft = IntOffset(left, top)
                // 默认合成策略在半透明时作用于整条文字带，不能改为逐笔透明度。
                record(density, direction, bandSize) {
                    translate(-left.toFloat(), -top.toFloat()) {
                        clipRect(-overhang, bandTop, width + overhang, bandBottom) {
                            drawLayer(source)
                        }
                    }
                }
            }
        }
        composite.record(density, direction, IntSize(ceil(width).toInt(), ceil(height).toInt())) {
            layers.forEach { drawLayer(it) }
        }
    }

    fun draw(scope: DrawScope, alphas: List<Float>) {
        layers.forEachIndexed { index, layer -> layer.alpha = alphas[index] }
        with(scope) { drawLayer(composite) }
    }
}
