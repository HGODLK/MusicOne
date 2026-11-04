package com.musicone

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.os.Build
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import kotlin.math.atan2
import kotlin.math.hypot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * 采样后方页面的实时图层，在独立离屏图层中模糊，前景文字与按钮保持清晰。
 * 扩大采样范围后再裁剪，避免玻璃边缘因缺少背景像素而出现透明边。
 * visualOffset 在绘制阶段移动已于最终位置合成的完整玻璃快照，避免动画逐帧重组模糊子树。
 */
@Composable
fun MusicOneBackdropGlass(
    backdropLayer: GraphicsLayer?,
    backdropBounds: Rect,
    modifier: Modifier = Modifier,
    shape: Shape,
    blurRadius: Dp = 16.dp,
    containerColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = .58f),
    fallbackColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    border: BorderStroke? = BorderStroke(.75.dp, MaterialTheme.colorScheme.surface.copy(alpha = .68f)),
    shadowElevation: Dp = 0.dp,
    backgroundSnapshot: GraphicsLayer? = null,
    captureBackgroundSnapshot: Boolean = true,
    visualOffset: (() -> Offset)? = null,
    content: @Composable () -> Unit,
) {
    val blurredLayer = rememberGraphicsLayer()
    val movingGlassLayer = rememberGraphicsLayer()
    var surfaceBounds by remember { mutableStateOf(Rect.Zero) }
    var surfaceCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val menuSampling = LocalPlayerMenuSampling.current
    val hasBackdrop =
        !ExperiencePreferences.options.disableBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && backdropLayer != null && backdropBounds.width > 0f && backdropBounds.height > 0f
    val resolvedContainerColor = if (hasBackdrop) containerColor else fallbackColor
    val liveSampling = LocalMiniPlayerGlassSampling.current?.takeIf { it.source === backdropLayer }
    val liveRecording = if (liveSampling == null) null
        else rememberMiniPlayerGlassRecording(liveSampling, backdropLayer, hasBackdrop)

    val movementModifier = if (visualOffset == null) {
        Modifier
    } else {
        Modifier.drawWithContent {
            movingGlassLayer.record { this@drawWithContent.drawContent() }
            val offset = visualOffset()
            translate(left = offset.x, top = offset.y) {
                drawLayer(movingGlassLayer)
            }
        }
    }
    // 槽位始终停在最终布局坐标，绘制时先合成完整玻璃，再只平移快照。
    Box(
        modifier = modifier
            .onGloballyPositioned { surfaceBounds = it.boundsInRoot(); surfaceCoordinates = it }
            .then(movementModifier),
        propagateMinConstraints = true,
    ) {
        Surface(
            shape = shape,
            // 模糊采样偶发失效时仍保留玻璃底板，不能只剩前景图标。
            color = fallbackColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = border,
            shadowElevation = shadowElevation,
        ) {
            Box {
                Box(Modifier.matchParentSize().drawWithContent {
                    if (backgroundSnapshot != null) {
                        if (captureBackgroundSnapshot || backgroundSnapshot.size.width <= 0 ||
                            backgroundSnapshot.size.height <= 0
                        ) {
                            backgroundSnapshot.record { this@drawWithContent.drawContent() }
                        }
                        drawLayer(backgroundSnapshot)
                    } else drawContent()
                }) {
                    val layer = backdropLayer
                    if (hasBackdrop && layer != null && surfaceBounds.width > 0f) {
                        Box(
                            Modifier.matchParentSize().clip(shape).drawWithCache {
                                val radius = blurRadius.toPx().coerceAtLeast(0f)
                                val inset = ceil(radius * 2).toInt()
                                // 只降低背景模糊分支，前景与圆角裁剪保持原分辨率。
                                val sampleScale = 1f / 4f
                                // 缓存模糊参数，实时刷新只重录下方局部采样。
                                blurredLayer.renderEffect = if (radius > 0f) {
                                    BlurEffect(radius * sampleScale, radius * sampleScale, TileMode.Clamp)
                                } else null
                                val sampledSize = IntSize(
                                    ceil((size.width + inset * 2) * sampleScale).toInt().coerceAtLeast(1),
                                    ceil((size.height + inset * 2) * sampleScale).toInt().coerceAtLeast(1),
                                )
                                val sampleDensity = Density(density, fontScale)
                                val sampleDirection = layoutDirection
                                // 仅播放 bar 在缓存阶段观察位置，位置改变也要作废同版本录制。
                                val liveBounds = if (liveRecording != null) surfaceBounds else null
                                val recordSample: () -> Unit = {
                                    if (surfaceBounds.width > 0f) blurredLayer.record(
                                        density = sampleDensity, layoutDirection = sampleDirection, size = sampledSize,
                                    ) {
                                        // 先铺满不透明底色，再模糊页面内容，防止原始文字和图标从透明像素中透出。
                                        drawRect(fallbackColor.copy(alpha = 1f))
                                        scale(sampleScale, sampleScale, Offset.Zero) {
                                            val coordinates = surfaceCoordinates
                                            val sampledOrigin = coordinates?.let { menuSampling?.pointInSurface(it, backdropBounds.topLeft) }
                                            if (sampledOrigin != null && menuSampling != null) {
                                                // 旋转菜单先将物理背景逆变换到当前表面，再沿用原降采样模糊。
                                                val origin = sampledOrigin
                                                val x = menuSampling.pointInSurface(coordinates, backdropBounds.topLeft + Offset(1f, 0f))!! - origin
                                                val y = menuSampling.pointInSurface(coordinates, backdropBounds.topLeft + Offset(0f, 1f))!! - origin
                                                val angle = (atan2(x.y, x.x) * 180f / Math.PI).toFloat()
                                                translate(inset + origin.x, inset + origin.y) {
                                                    rotate(angle, Offset.Zero) {
                                                        scale(hypot(x.x, x.y), hypot(y.x, y.y), Offset.Zero) { drawLayer(layer) }
                                                    }
                                                }
                                            } else {
                                                val sampledBounds = liveBounds ?: surfaceBounds
                                                translate(
                                                    left = backdropSampleTranslation(
                                                        backdropBounds.left,
                                                        sampledBounds.left,
                                                        inset.toFloat(),
                                                    ),
                                                    top = backdropSampleTranslation(
                                                        backdropBounds.top,
                                                        sampledBounds.top,
                                                        inset.toFloat(),
                                                    ),
                                                ) {
                                                    drawLayer(layer)
                                                }
                                            }
                                        }
                                    }
                                }
                                if (liveRecording != null && liveBounds != null) {
                                    liveRecording.configure(MiniPlayerGlassSampleKey(liveBounds, backdropBounds, sampledSize,
                                        sampleDensity, sampleDirection, radius, fallbackColor), recordSample)
                                }
                                onDrawBehind {
                                    if (liveRecording == null) recordSample()
                                    else liveRecording.refresh(liveSampling!!.updates.version())
                                    translate(left = -inset.toFloat(), top = -inset.toFloat()) {
                                        scale(1f / sampleScale, 1f / sampleScale, Offset.Zero) { drawLayer(blurredLayer) }
                                    }
                                }
                            },
                        )
                    }
                    Box(
                        Modifier.matchParentSize().clip(shape).background(resolvedContainerColor)
                    )
                }
                if (liveSampling == null) content() else {
                    // 只有本层播放 bar 主动订阅；前景里再打开的玻璃组件保持原行为。
                    androidx.compose.runtime.CompositionLocalProvider(LocalMiniPlayerGlassSampling provides null) { content() }
                }
            }
        }
    }
}

internal fun backdropSampleTranslation(
    backdropStart: Float,
    surfaceStart: Float,
    sampleInset: Float,
): Float = sampleInset + backdropStart - surfaceStart
