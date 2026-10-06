package com.musicone.demo

import android.os.Build
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private class PlayerGlassSnapshotCache {
    var moving = false
    var ready = false
    var hostSize = IntSize.Zero
    var sampleScale = 1f

    fun reset() {
        moving = false
        ready = false
        hostSize = IntSize.Zero
    }
}

/**
 * 转场前预模糊首页和播放页快照；动画期间只在固定 Canvas 中裁剪、混合和绘制。
 * 进度不进入组合和测量阶段，避免扩张玻璃逐帧触发重组与双重模糊。
 */
@Composable
internal fun FlyingPlayerGlass(motion: PageMotion) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .3f
    val sourceLayer = LocalPlayerBackdropTexture.current
    val targetLayer = LocalPlayerAtmosphereTexture.current
    val fallbackLayer = LocalPlayerSurfaceTexture.current
    val backdropBounds = LocalPlayerBackdropBounds.current
    val sourceSnapshot = rememberGraphicsLayer()
    val targetSnapshot = rememberGraphicsLayer()
    val path = remember { Path() }
    val sourcePath = remember { Path() }
    val paint = remember { Paint() }
    val cache = remember { PlayerGlassSnapshotCache() }
    val prewarming = LocalPlayerPrewarming.current

    Canvas(Modifier.fillMaxSize()) {
        val preparing = motion.phase == MotionPhase.PREPARING
        if (!motion.moving && !preparing && !prewarming) {
            cache.reset()
            return@Canvas
        }
        val hostWidth = motion.hostBounds.width.roundToInt().coerceAtLeast(1)
        val hostHeight = motion.hostBounds.height.roundToInt().coerceAtLeast(1)
        val hostSize = IntSize(hostWidth, hostHeight)
        val canPrepare = !ExperiencePreferences.options.disableBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            sourceLayer != null && targetLayer != null &&
            sourceLayer.size.width > 0 && sourceLayer.size.height > 0 &&
            targetLayer.size.width > 0 && targetLayer.size.height > 0

        if (!cache.moving || preparing || cache.hostSize != hostSize || (!cache.ready && canPrepare)) {
            cache.moving = true
            cache.hostSize = hostSize
            cache.ready = false
            if (canPrepare) {
                val sampleScale = playerGlassSampleScale(hostWidth, hostHeight)
                val sampledSize = IntSize(
                    (hostWidth * sampleScale).roundToInt().coerceAtLeast(1),
                    (hostHeight * sampleScale).roundToInt().coerceAtLeast(1),
                )
                val blur = 18.dp.toPx() * sampleScale
                sourceSnapshot.renderEffect = BlurEffect(blur, blur, TileMode.Clamp)
                targetSnapshot.renderEffect = BlurEffect(blur, blur, TileMode.Clamp)
                sourceSnapshot.record(size = sampledSize) {
                    scale(sampleScale, sampleScale, Offset.Zero) {
                        translate(
                            left = backdropBounds.left - motion.hostBounds.left,
                            top = backdropBounds.top - motion.hostBounds.top,
                        ) { drawLayer(sourceLayer) }
                    }
                }
                targetSnapshot.record(size = sampledSize) {
                    scale(sampleScale, sampleScale, Offset.Zero) { drawLayer(targetLayer) }
                }
                cache.sampleScale = sampleScale
                cache.ready = true
            }
        }

        if (prewarming) {
            // 遮罩覆盖期间实际使用模糊图层，让首次栅格化不落在用户展开的首帧。
            if (cache.ready) { drawLayer(sourceSnapshot); drawLayer(targetSnapshot) }
            return@Canvas
        }
        if (preparing) return@Canvas
        val source = motion.sourceSnapshot["surface"] ?: return@Canvas
        val target = motion.targetSnapshot["surface"] ?: return@Canvas

        val progress = motion.value
        val bounds = motionRect(source.bounds, target.bounds, progress)
            .translate(-motion.hostBounds.left, -motion.hostBounds.top)
        val sourceBounds = source.bounds.translate(-motion.hostBounds.left, -motion.hostBounds.top)
        val corner = playerSurfaceCorner(progress).dp.toPx()
        val alpha = playerGlassAlpha(progress)
        path.reset()
        path.addRoundRect(RoundRect(bounds, CornerRadius(corner)))

        if (cache.ready) {
            sourceSnapshot.alpha = alpha * (1f - progress)
            targetSnapshot.alpha = alpha * progress
            clipPath(path) {
                withTransform({ scale(1f / cache.sampleScale, 1f / cache.sampleScale, Offset.Zero) }) {
                    drawLayer(sourceSnapshot)
                    drawLayer(targetSnapshot)
                }
            }
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
        } else if (ExperiencePreferences.options.disableBlur) {
            val fallback = if (dark) Color(0xFF242526) else Color(0xFFF6F7F8)
            drawRoundRect(fallback.copy(alpha = alpha), bounds.topLeft, bounds.size, CornerRadius(corner))
        } else if (fallbackLayer != null && fallbackLayer.size.width > 0 && fallbackLayer.size.height > 0) {
            paint.alpha = alpha
            drawContext.canvas.saveLayer(bounds, paint)
            clipPath(path) {
                withTransform({
                    translate(bounds.left, bounds.top)
                    scale(bounds.width / fallbackLayer.size.width, bounds.height / fallbackLayer.size.height, Offset.Zero)
                }) { drawLayer(fallbackLayer) }
            }
            drawContext.canvas.restore()
        } else {
            drawRoundRect(
                color = (if (dark) Color(0xFF242526) else Color(0xFFF6F7F8)).copy(alpha = alpha),
                topLeft = bounds.topLeft,
                size = bounds.size,
                cornerRadius = CornerRadius(corner),
            )
        }
        val sourceHandoffAlpha = playerGlassSourceHandoffAlpha(progress) * alpha
        if (sourceHandoffAlpha > .001f && fallbackLayer != null &&
            fallbackLayer.size.width > 0 && fallbackLayer.size.height > 0
        ) {
            // 末段覆盖为静态迷你玻璃自身保存的最终合成结果，使模糊与亮度在交接帧完全一致。
            sourcePath.reset()
            sourcePath.addRoundRect(RoundRect(sourceBounds, CornerRadius(19.dp.toPx())))
            paint.alpha = sourceHandoffAlpha
            drawContext.canvas.saveLayer(sourceBounds, paint)
            clipPath(path) {
                clipPath(sourcePath) {
                    withTransform({
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
            drawRoundRect(
                // 与迷你播放器的静态边框使用同一颜色，并完整覆盖到交接帧。
                color = if (dark) {
                    Color.White.copy(alpha = .16f * borderAlpha)
                } else {
                    Color(0xFFE0E2E6).copy(alpha = borderAlpha)
                },
                topLeft = bounds.topLeft,
                size = bounds.size,
                cornerRadius = CornerRadius(corner),
                style = Stroke(1.dp.toPx()),
            )
        }
    }
}

internal fun playerGlassSampleScale(widthPx: Int, heightPx: Int): Float {
    val longEdge = maxOf(widthPx, heightPx).coerceAtLeast(1)
    return minOf(.5f, 1_440f / longEdge).coerceAtLeast(.35f)
}

internal fun playerGlassTintAlpha(progress: Float): Float = motionLerp(.28f, .16f, progress)

internal fun playerGlassDarkTintAlpha(progress: Float): Float = motionLerp(.82f, .58f, progress)

/** 在源端短距离内平滑切换到静态玻璃的精确合成快照，终点不再改变亮度或模糊。 */
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
