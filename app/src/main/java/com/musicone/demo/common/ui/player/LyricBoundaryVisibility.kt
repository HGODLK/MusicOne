package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** 与玻璃绘制共用原点；开合位移只改变换算，不改变列表布局。 */
internal class LyricBoundaryWindow(
    val topBuffer: Float,
    val offset: () -> Float,
    val velocity: () -> Float = { 0f },
)

internal fun lyricBoundaryLineAlpha(top: Float, bottom: Float, viewportTop: Float,
    viewportBottom: Float, fadePx: Float): Float {
    if (bottom <= viewportTop || top >= viewportBottom) return 0f
    // 顶部仍保护完整字形；底部从文字上缘进入时开始显现，让原列表位移连续滑入。
    val clearance = min(top - viewportTop, viewportBottom - top)
    val p = (clearance / fadePx.coerceAtLeast(1f)).coerceIn(0f, 1f)
    return p * p * (3f - 2f * p)
}

internal class LyricLineBoundary(val window: LyricBoundaryWindow,
    val rowTop: () -> Float?, val viewportHeight: () -> Float)

/** 按真实排版行保护字形；新补入的行沿列表位移和边界位置自然显现。 */
@Composable
internal fun Modifier.lyricTextBoundary(
    boundary: LyricLineBoundary?,
    layout: () -> TextLayoutResult?,
    rowCoordinates: () -> LayoutCoordinates?,
): Modifier {
    if (boundary == null) return this
    val coordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }
    val latestBoundary = rememberUpdatedState(boundary)
    val drawing = remember(layout, rowCoordinates, coordinates) {
        Modifier.onGloballyPositioned { coordinates.value = it }.drawWithCache {
            val result = layout()
            val bands = result?.let { text -> (0 until text.lineCount).map { line ->
                text.getLineTop(line) to text.getLineBottom(line)
            } }.orEmpty()
            val fadePx = 8.dp.toPx()
            val overhang = 6.dp.toPx()
            // 透明度相同的帧不触发边界重绘，裁剪节点仅在首次经过边界时创建。
            var scene: LyricBoundaryRenderScene? = null
            var source: GraphicsLayer? = null
            val lineAlphas = derivedStateOf(structuralEqualityPolicy()) {
                val currentBoundary = latestBoundary.value
                val window = currentBoundary.window
                val root = rowCoordinates()
                val child = coordinates.value
                val itemTop = currentBoundary.rowTop()
                if (result == null || root?.isAttached != true || child?.isAttached != true || itemTop == null) {
                    emptyList()
                } else {
                    val relativeOrigin = root.localPositionOf(child, Offset.Zero)
                    val origin = relativeOrigin + Offset(0f, itemTop)
                    val textScale = (root.localPositionOf(child, Offset(0f, 1f)).y -
                        relativeOrigin.y).coerceAtLeast(.001f)
                    val shift = window.offset()
                    val top = (window.topBuffer - shift).coerceAtLeast(0f)
                    val viewportHeight = currentBoundary.viewportHeight()
                    val bottom = (viewportHeight - shift).coerceAtMost(viewportHeight)
                    bands.map { (lineTop, lineBottom) ->
                        lyricBoundaryLineAlpha(origin.y + lineTop * textScale, origin.y + lineBottom * textScale,
                            top, bottom, fadePx)
                    }
                }
            }
            onDrawWithContent {
                val alphas = lineAlphas.value
                if (alphas.isEmpty()) return@onDrawWithContent
                if (alphas.all { it == 1f }) {
                    drawContent()
                    return@onDrawWithContent
                }
                if (alphas.all { it == 0f }) return@onDrawWithContent
                // 始终更新真实内容，裁剪节点只引用它；逐字动画不受边界缓存影响。
                val currentSource = source ?: obtainGraphicsLayer().also { source = it }
                currentSource.record { this@onDrawWithContent.drawContent() }
                val currentScene = scene ?: LyricBoundaryRenderScene(this@drawWithCache, currentSource, bands, overhang)
                    .also { scene = it }
                currentScene.draw(this, alphas)
            }
        // 边界只重放内部节点；逐字内容变化仍由内部绘制失效更新，遮罩保持实时。
        }.graphicsLayer()
    }
    return this.then(drawing)
}
