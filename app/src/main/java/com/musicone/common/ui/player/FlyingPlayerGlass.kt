package com.musicone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.math.ceil

private class PlayerGlassSnapshotCache {
    var ready = false
    var hostSize = IntSize.Zero
    var sampleScale = 1f
    var source: GraphicsLayer? = null
    var target: GraphicsLayer? = null
    var sourceSize = IntSize.Zero
    var targetSize = IntSize.Zero
    var sourceOffset = Offset.Zero
    var blurPixels = 0f
    var nativeReady = false

    fun reset() {
        ready = false
        nativeReady = false
        hostSize = IntSize.Zero
        source = null
        target = null
    }
}

/**
 * 播放页展开/收起时的飞行毛玻璃背景：转场前先缓存模糊图层，动画期间只在属性阶段更新变换，
 * 不把动画进度带入组合和测量阶段，避免逐帧重组和重复模糊。
 */
@Composable
internal fun FlyingPlayerGlass(motion: PageMotion) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .3f
    val handoff = LocalPlayerControlHandoff.current
    val defaultBorderColor = if (dark) Color.White.copy(alpha = .16f) else Color(0xFFE0E2E6)
    val transitionBackdrop = LocalPlayerTransitionBackdrop.current
    val sourceLayer = transitionBackdrop?.layer ?: LocalPlayerBackdropTexture.current
    val targetLayer = LocalPlayerAtmosphereTexture.current
    val fallbackLayer = LocalPlayerSurfaceTexture.current
    val backdropBounds = transitionBackdrop?.bounds ?: LocalPlayerBackdropBounds.current
    val sourceSnapshot = rememberGraphicsLayer()
    val targetSnapshot = rememberGraphicsLayer()
    val clippedBackground = rememberGraphicsLayer()
    val fixedBackground = rememberGraphicsLayer()
    val path = remember { Path() }
    val sourcePath = remember { Path() }
    val paint = remember { Paint() }
    val cache = remember { PlayerGlassSnapshotCache() }
    val flight = LocalPhonePlayerFlightRotation.current
    val rotation = LocalPhonePlayerRotation.current
    val surfaceFrame = LocalPlayerSurfaceFlightFrame.current
        ?: remember(motion, flight, rotation) { playerSurfaceFlightState(motion, flight, rotation) }
    val pathMatrix = remember { Matrix() }
    val prewarming = LocalPlayerPrewarming.current
    val preparation = LocalPlayerOpeningLayers.current
    val density = LocalDensity.current
    val nativeBackground = remember(sourceSnapshot, targetSnapshot, fixedBackground, clippedBackground) {
        PlayerGlassNativeBackground(sourceSnapshot, targetSnapshot, fixedBackground, clippedBackground)
    }
    val nativeFrame = remember(motion, flight, rotation, prewarming, surfaceFrame, density, sourceLayer, targetLayer) {
        playerGlassNativeFrameState(motion, flight, rotation, prewarming, surfaceFrame, density) {
            cache.ready && playerGlassCanPrepare(sourceLayer, targetLayer)
        }
    }

    Box(Modifier.fillMaxSize()) {
    PlayerGlassNativeBackgroundLayer(nativeBackground, nativeFrame) { cache.sampleScale }
    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
        // 先读取本帧是否走原生图层路径，确保和缓存准备保持一致，不会多画或漏画一遍模糊背景。
        val native = nativeFrame.value
        val preparing = motion.phase == MotionPhase.PREPARING
        if (!motion.moving && !preparing && !prewarming) {
            // 静止时保留预热好的模糊图层缓存，下次启动前再核对尺寸、偏移和模糊半径是否变化。
            return@Canvas
        }
        val hostWidth = motion.hostBounds.width.roundToInt().coerceAtLeast(1)
        val hostHeight = motion.hostBounds.height.roundToInt().coerceAtLeast(1)
        val hostSize = IntSize(hostWidth, hostHeight)
        val canPrepare = playerGlassCanPrepare(sourceLayer, targetLayer)
        val sourceOffset = backdropBounds.topLeft - motion.hostBounds.topLeft
        val sampleScale = playerGlassSampleScale(hostWidth, hostHeight)
        val blurPixels = 18.dp.toPx() * sampleScale
        // 准备阶段虽会重绘，但图层参数没变时只录制一次；子图层本身仍会实时反映切歌和背景变化。
        val snapshotChanged = cache.hostSize != hostSize || cache.source !== sourceLayer ||
            cache.target !== targetLayer || cache.sourceSize != sourceLayer?.size ||
            cache.targetSize != targetLayer?.size || cache.sourceOffset != sourceOffset || cache.blurPixels != blurPixels
        if (!canPrepare) cache.reset()
        if (snapshotChanged || (!cache.ready && canPrepare)) {
            cache.hostSize = hostSize
            cache.ready = false
            if (canPrepare && sourceLayer != null && targetLayer != null) {
                val sampledSize = IntSize(
                    (hostWidth * sampleScale).roundToInt().coerceAtLeast(1),
                    (hostHeight * sampleScale).roundToInt().coerceAtLeast(1),
                )
                sourceSnapshot.renderEffect = BlurEffect(blurPixels, blurPixels, TileMode.Clamp)
                targetSnapshot.renderEffect = BlurEffect(blurPixels, blurPixels, TileMode.Clamp)
                sourceSnapshot.record(size = sampledSize) {
                    scale(sampleScale, sampleScale, Offset.Zero) {
                        translate(
                            left = sourceOffset.x,
                            top = sourceOffset.y,
                        ) { drawLayer(sourceLayer) }
                    }
                }
                targetSnapshot.record(size = sampledSize) {
                    scale(sampleScale, sampleScale, Offset.Zero) { drawLayer(targetLayer) }
                }
                cache.nativeReady = false
                cache.sampleScale = sampleScale
                cache.source = sourceLayer
                cache.target = targetLayer
                cache.sourceSize = sourceLayer.size
                cache.targetSize = targetLayer.size
                cache.sourceOffset = sourceOffset
                cache.blurPixels = blurPixels
                cache.ready = true
            }
        }

        val rotated = flight != null && (rotation != 0 || flight.handoffActive)
        if (cache.ready && !cache.nativeReady && (rotated || prewarming)) {
            // 提前建好横屏用的合成图层，动画开始后只需改裁剪轮廓、透明度和旋转角度。
            // 背景混合保持采样尺寸，最后才放大；外层轮廓与描边仍按完整分辨率绘制。
            fixedBackground.record(size = sourceSnapshot.size) {
                drawLayer(sourceSnapshot)
                drawLayer(targetSnapshot)
            }
            clippedBackground.record(size = hostSize) {
                scale(1f / cache.sampleScale, 1f / cache.sampleScale, Offset.Zero) {
                    drawLayer(fixedBackground)
                }
            }
            clippedBackground.setRoundRectOutline(Offset.Zero,
                androidx.compose.ui.geometry.Size(hostWidth.toFloat(), hostHeight.toFloat()), 0f)
            clippedBackground.clip = true
            cache.nativeReady = true
            // 图层重建后立即补同步本帧参数，避免留下全屏裁剪轮廓。
            if (native != null) nativeBackground.apply(native, cache.sampleScale)
        }
        if (prewarming) {
            // 启动预热时先实际绘制一遍模糊图层，把首次栅格化开销提前消化掉。
            if (cache.ready) {
                sourceSnapshot.alpha = 1f
                targetSnapshot.alpha = 1f
                fixedBackground.rotationZ = 0f
                clippedBackground.rotationZ = 0f
                clippedBackground.setRoundRectOutline(Offset.Zero,
                    androidx.compose.ui.geometry.Size(hostWidth.toFloat(), hostHeight.toFloat()), 0f)
                drawLayer(sourceSnapshot)
                drawLayer(targetSnapshot)
                drawLayer(clippedBackground)
            }
            return@Canvas
        }
        if (preparing) {
            // 标记玻璃层已准备就绪（不支持模糊时走纯色回退也算就绪）。
            preparation?.record(PlayerOpeningLayer.GLASS, hostSize)
            return@Canvas
        }
        if (motion.targetSnapshot["surface"] == null) return@Canvas
        val sharedFrame = surfaceFrame.value ?: return@Canvas

        val progress = motion.value
        PlayerMotionDiagnostics.changed("glass-branch") {
            "phase=${motion.phase} zero=${progress == 0f} fallback=${fallbackLayer != null} cache=${cache.ready} " +
                "border=${playerGlassBorderAlpha(progress) > .001f}"
        }
        val geometry = sharedFrame.geometry
        val sourceGeometry = sharedFrame.sourceGeometry
        val bounds = geometry.bounds.translate(-motion.hostBounds.left, -motion.hostBounds.top)
        val sourceBounds = sourceGeometry.bounds.translate(-motion.hostBounds.left, -motion.hostBounds.top)
        val corner = playerSurfaceCorner(progress).dp.toPx()
        val alpha = playerGlassAlpha(progress)
        val sourceHandoffAlpha = playerGlassSourceHandoffAlpha(progress) * alpha
        fun rotatePath(outline: Path, center: Offset, angle: Float) {
            if (angle == 0f) return
            pathMatrix.reset()
            pathMatrix.translate(center.x, center.y)
            pathMatrix.rotateZ(angle)
            pathMatrix.translate(-center.x, -center.y)
            outline.transform(pathMatrix)
        }
        if (!rotated || !cache.ready || progress == 0f || sourceHandoffAlpha > .001f) {
            // 末段与迷你条交接或未启用原生路径时，用 Path 裁剪圆角矩形。
            path.reset()
            path.addRoundRect(RoundRect(bounds, CornerRadius(corner)))
            rotatePath(path, bounds.center, geometry.rotation)
        }

        if (progress == 0f && fallbackLayer != null &&
            fallbackLayer.size.width > 0 && fallbackLayer.size.height > 0) {
            // 进度归零时直接画迷你条的背景快照，再配合下方描边实现无缝交接。
            clipPath(path) {
                withTransform({
                    if (sourceGeometry.rotation != 0f) rotate(sourceGeometry.rotation, sourceBounds.center)
                    translate(sourceBounds.left, sourceBounds.top)
                    scale(sourceBounds.width / fallbackLayer.size.width,
                        sourceBounds.height / fallbackLayer.size.height, Offset.Zero)
                }) { drawLayer(fallbackLayer) }
            }
        } else if (cache.ready && alpha > 0f) {
            if (native == null) {
                sourceSnapshot.alpha = alpha * (1f - progress)
                targetSnapshot.alpha = alpha * progress
            }
            if (rotated) {
                if (native == null) {
                    clippedBackground.setRoundRectOutline(bounds.topLeft, bounds.size, corner)
                    clippedBackground.pivotOffset = bounds.center
                    clippedBackground.rotationZ = geometry.rotation
                    fixedBackground.pivotOffset = bounds.center * cache.sampleScale
                    fixedBackground.rotationZ = -geometry.rotation
                    drawLayer(clippedBackground)
                }
            } else clipPath(path) {
                scale(1f / cache.sampleScale, 1f / cache.sampleScale, Offset.Zero) {
                    drawLayer(sourceSnapshot)
                    drawLayer(targetSnapshot)
                }
            }
            withTransform({ if (geometry.rotation != 0f) rotate(geometry.rotation, bounds.center) }) {
            drawRoundRect(
                color = if (dark) {
                    Color(0xFF242526).copy(alpha = playerGlassDarkTintAlpha(progress) * alpha)
                } else {
                    Color.White.copy(alpha = playerGlassTintAlpha(progress) * alpha)
                },
                topLeft = bounds.topLeft,
                size = bounds.size,
                cornerRadius = CornerRadius(corner),
            )
            }
        } else if (cache.ready) {
            // 模糊底板完全透明后跳过绘制，只留下面的描边继续淡出。
        } else if (ExperiencePreferences.options.disableBlur) {
            val fallback = if (dark) Color(0xFF242526) else Color(0xFFF6F7F8)
            if (geometry.rotation == 0f) drawRoundRect(fallback.copy(alpha = alpha), bounds.topLeft, bounds.size, CornerRadius(corner))
            else drawPath(path, fallback.copy(alpha = alpha))
        } else if (fallbackLayer != null && fallbackLayer.size.width > 0 && fallbackLayer.size.height > 0) {
            paint.alpha = alpha
            drawContext.canvas.saveLayer(phonePlayerFlightBounds(bounds, geometry.rotation), paint)
            clipPath(path) {
                withTransform({
                    if (geometry.rotation != 0f) rotate(geometry.rotation, bounds.center)
                    translate(bounds.left, bounds.top)
                    scale(bounds.width / fallbackLayer.size.width, bounds.height / fallbackLayer.size.height, Offset.Zero)
                }) { drawLayer(fallbackLayer) }
            }
            drawContext.canvas.restore()
        } else {
            withTransform({ if (geometry.rotation != 0f) rotate(geometry.rotation, bounds.center) }) {
            drawRoundRect(
                color = (if (dark) Color(0xFF242526) else Color(0xFFF6F7F8)).copy(alpha = alpha),
                topLeft = bounds.topLeft,
                size = bounds.size,
                cornerRadius = CornerRadius(corner),
            )
            }
        }
        if (progress != 0f && sourceHandoffAlpha > .001f && fallbackLayer != null &&
            fallbackLayer.size.width > 0 && fallbackLayer.size.height > 0
        ) {
            // 接近迷你条时叠加上静态迷你玻璃快照，保证落地帧的模糊和亮度完全一致。
            sourcePath.reset()
            sourcePath.addRoundRect(RoundRect(sourceBounds, CornerRadius(MiniPlayerSurfaceCorner.dp.dp.toPx())))
            rotatePath(sourcePath, sourceBounds.center, sourceGeometry.rotation)
            paint.alpha = sourceHandoffAlpha
            drawContext.canvas.saveLayer(phonePlayerFlightBounds(sourceBounds, sourceGeometry.rotation), paint)
            clipPath(path) {
                clipPath(sourcePath) {
                    withTransform({
                        if (sourceGeometry.rotation != 0f) rotate(sourceGeometry.rotation, sourceBounds.center)
                        translate(sourceBounds.left, sourceBounds.top)
                        scale(
                            sourceBounds.width / fallbackLayer.size.width,
                            sourceBounds.height / fallbackLayer.size.height,
                            Offset.Zero,
                        )
                    }) { drawLayer(fallbackLayer) }
                }
            }
            drawContext.canvas.restore()
        }
        val borderAlpha = playerGlassBorderAlpha(progress)
        if (borderAlpha > .001f) {
            val capturedBorder = handoff?.sourceInk("surfaceBorder", defaultBorderColor) ?: defaultBorderColor
            val liveBorder = handoff?.currentInk("surfaceBorder", capturedBorder) ?: capturedBorder
            val borderColor = lerp(capturedBorder, liveBorder, playerGlassSourceHandoffAlpha(progress))
            // 与 Compose BorderStroke 共用向内描边及像素取整，终点外缘保持原矩形。
            val stroke = ceil(1.dp.toPx())
            val inset = stroke / 2f
            withTransform({ if (geometry.rotation != 0f) rotate(geometry.rotation, bounds.center) }) {
            drawRoundRect(
                // 复用真实迷你播放条的描边，包括网易云背景自适应颜色和原始透明度。
                color = borderColor.copy(alpha = borderColor.alpha * borderAlpha),
                topLeft = bounds.topLeft + Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(
                    (bounds.width - stroke).coerceAtLeast(0f), (bounds.height - stroke).coerceAtLeast(0f)),
                cornerRadius = CornerRadius((corner - inset).coerceAtLeast(0f)),
                style = Stroke(stroke),
            )
            }
        }
    }
    }
}

internal fun playerGlassSampleScale(widthPx: Int, heightPx: Int): Float {
    val longEdge = maxOf(widthPx, heightPx).coerceAtLeast(1)
    return minOf(.4f, 1_440f / longEdge).coerceAtLeast(.25f)
}

internal fun playerGlassTintAlpha(progress: Float): Float = motionLerp(MiniPlayerSurfaceTint.light, .16f, progress)

internal fun playerGlassDarkTintAlpha(progress: Float): Float = motionLerp(.82f, .58f, progress)

/** 接近迷你条（进度 0~0.16）时平滑过渡到静态玻璃快照，消除落地瞬间的亮度跳变。 */
internal fun playerGlassSourceHandoffAlpha(progress: Float): Float {
    val remaining = 1f - (progress.coerceIn(0f, 1f) / .16f).coerceIn(0f, 1f)
    return remaining * remaining * (3f - 2f * remaining)
}

internal fun playerGlassBorderAlpha(progress: Float): Float {
    val remaining = 1f - progress.coerceIn(0f, 1f)
    return remaining * remaining
}

internal fun playerGlassAlpha(progress: Float): Float {
    val p = ((progress.coerceIn(0f, 1f) - .12f) / .7f).coerceIn(0f, 1f)
    val eased = p * p * (3f - 2f * p)
    return 1f - eased
}
