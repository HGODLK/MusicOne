package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
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
    val edges: ((Float, Float) -> LyricBoundaryEdges)? = null,
    val partialTop: Boolean = false,
)

internal fun lyricBoundaryLineAlpha(top: Float, bottom: Float, viewportTop: Float,
    viewportBottom: Float, fadePx: Float, partialTop: Boolean = false): Float {
    if (bottom <= viewportTop || top >= viewportBottom) return 0f
    // 横屏已有固定空间渐隐，顶部保留尚未滑出的字形，不再提前淡掉整条排版行。
    // 竖屏顶部仍保护完整字形；底部从文字上缘进入时开始显现。
    val clearance = min(if (partialTop) fadePx else top - viewportTop, viewportBottom - top)
    val p = (clearance / fadePx.coerceAtLeast(1f)).coerceIn(0f, 1f)
    return p * p * (3f - 2f * p)
}

internal class LyricLineBoundary(val window: LyricBoundaryWindow,
    val rowTop: () -> Float?, val viewportHeight: () -> Float,
    val geometry: LyricViewportGeometry? = null,
    val viewport: LyricBoundaryViewport = LyricBoundaryViewport(window, viewportHeight))

/** 竖屏普通开合只在透明度变化时重录正文；转向、扩展窗口和横屏继续逐帧读取实际边界。 */
internal fun lyricBoundaryCachesAlphas(extendedEdges: Boolean, partialTop: Boolean,
    turnActive: Boolean, hasTransform: Boolean): Boolean =
    !extendedEdges && !partialTop && !turnActive && hasTransform

/** 未取得新坐标不等于不可见，交给原文字和外层实际视口裁剪。 */
internal fun lyricBoundaryDrawsContent(alphas: List<Float>): Boolean = alphas.isEmpty() || alphas.all { it == 1f }

/** 按真实排版行保护字形；新补入的行沿列表位移和边界位置自然显现。 */
@Composable
internal fun Modifier.lyricTextBoundary(
    boundary: LyricLineBoundary?,
    layout: () -> TextLayoutResult?,
    rowCoordinates: () -> LayoutCoordinates?,
    prepare: Boolean = false,
    transform: LyricLineVisualTransform? = null,
    contentKey: Any? = null,
): Modifier {
    if (boundary == null) return this
    val coordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }
    val latestBoundary = rememberUpdatedState(boundary)
    val preparing = rememberUpdatedState(prepare)
    val latestTransform = rememberUpdatedState(transform)
    val contentEpoch = LocalPlayerLyricsContent.current?.drawEpoch
    val turn = rememberUpdatedState(LocalPhonePlayerTurn.current)
    val generation = LocalPhonePlayerTurn.current?.generation ?: 0
    val nativeOrigin = remember(boundary.geometry, generation) { mutableStateOf<Offset?>(null) }
    val root = rowCoordinates()
    fun recordOrigin(child: LayoutCoordinates?) {
        val parent = rowCoordinates()
        val visual = latestTransform.value
        if (parent?.isAttached == true && child?.isAttached == true && visual != null)
            nativeOrigin.value = lyricNativeTextOrigin(parent.localPositionOf(child, Offset.Zero),
                visual.pivot(), visual.scale(), visual.offset())
    }
    // 父行可能晚于文字发布坐标；补齐登记只发生在放置/重排，不加入逐帧矩阵遍历。
    SideEffect { if (root?.isAttached == true) recordOrigin(coordinates.value) }
    val drawing = remember(layout, rowCoordinates, coordinates, boundary.geometry, generation, contentKey) {
        Modifier.onGloballyPositioned {
            coordinates.value = it
            recordOrigin(it)
        }.lyricDrawWithCache {
            val result = layout()
            val bands = result?.let { text -> (0 until text.lineCount).map { line ->
                text.getLineTop(line) to text.getLineBottom(line)
            } }.orEmpty()
            val fadePx = 8.dp.toPx()
            val overhang = 6.dp.toPx()
            val alphaSampler = LyricBoundaryAlphaSampler(bands)
            // 只在实际绘制时读取边界，裁剪节点仍在首次经过边界时创建。
            var scene: LyricBoundaryRenderScene? = null
            var source: GraphicsLayer? = null
            var recordedEpoch = Long.MIN_VALUE
            fun prepareScene(draw: androidx.compose.ui.graphics.drawscope.ContentDrawScope): LyricBoundaryRenderScene {
                val currentSource = source ?: obtainGraphicsLayer().also { source = it }
                val epoch = contentEpoch?.revision ?: 0L
                // 换窗或 seek 后刷新引用；正常逐字帧仍由持久子图层独立更新。
                if (recordedEpoch != epoch) {
                    currentSource.record { draw.drawContent() }
                    recordedEpoch = epoch
                }
                return scene ?: LyricBoundaryRenderScene(this, currentSource, bands, overhang).also { scene = it }
            }
            val lineAlphas = {
                val currentBoundary = latestBoundary.value
                val root = rowCoordinates()
                val child = coordinates.value
                val itemTop = currentBoundary.rowTop()
                if (result == null || root?.isAttached != true || child?.isAttached != true || itemTop == null) {
                    emptyList()
                } else {
                    val visual = latestTransform.value
                    val native = nativeOrigin.value
                    val visualScale = if (visual != null && native != null) visual.scale() else null
                    val relativeOrigin = if (visual != null && native != null && visualScale != null)
                        lyricDrawnTextOrigin(native, visual.pivot(), visualScale, visual.offset())
                    else root.localPositionOf(child, Offset.Zero)
                    val origin = relativeOrigin + Offset(0f, itemTop)
                    val textScale = visualScale
                        ?: (root.localPositionOf(child, Offset(0f, 1f)).y - relativeOrigin.y).coerceAtLeast(.001f)
                    alphaSampler.sample(origin.y, textScale, currentBoundary.viewport.read(), fadePx,
                        currentBoundary.window.partialTop)
                }
            }
            val stableAlphas = derivedStateOf(structuralEqualityPolicy()) { lineAlphas() }
            onDrawWithContent {
                val window = latestBoundary.value.window
                val activeTurn = turn.value
                val alphas = if (lyricBoundaryCachesAlphas(window.edges != null, window.partialTop,
                        activeTurn != null && (activeTurn.isTurning || activeTurn.preparing || activeTurn.orientationChanging),
                        latestTransform.value != null && nativeOrigin.value != null)) stableAlphas.value
                    else lineAlphas()
                PlayerMotionDiagnostics.sample("boundary-draw/${System.identityHashCode(coordinates)}") {
                    lyricBoundaryDiagnostic(alphas, bands, latestBoundary.value, rowCoordinates(), coordinates.value) +
                        " canvasClip=${drawContext.canvas.nativeCanvas.clipBounds}"
                }
                // 有效坐标和文字均齐备后再录制，防止把首次空内容永久作为边界来源。
                val prepared = if (preparing.value && alphas.isNotEmpty()) prepareScene(this) else null
                if (lyricBoundaryDrawsContent(alphas)) {
                    drawContent()
                    return@onDrawWithContent
                }
                if (alphas.all { it == 0f }) return@onDrawWithContent
                val currentScene = prepared ?: prepareScene(this)
                currentScene.draw(this, alphas)
            }
        // 边界只重放内部节点；逐字内容变化仍由内部绘制失效更新，遮罩保持实时。
        }.graphicsLayer()
    }
    return this.then(drawing)
}
