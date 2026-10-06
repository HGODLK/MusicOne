package com.musicone.demo

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private class EntityGlassCache {
    var ready = false
    var size = IntSize.Zero
    var scale = 1f
}

/** 沿用播放页的降采样预模糊，整个返回表面共用一张玻璃底图，文字与头像独立交接。 */
@Composable
internal fun EntityPageGlass(frame: EntityFrame) {
    val fallbackLayer = LocalPlayerBackdropTexture.current
    val fallbackBounds = LocalPlayerBackdropBounds.current
    val layer = frame.sourceBackdrop ?: fallbackLayer
    val sourceBounds = if (frame.sourceBackdrop != null) frame.sourceBackdropBounds else fallbackBounds
    val blurred = rememberGraphicsLayer()
    val cache = remember(frame) { EntityGlassCache() }
    val surface = MaterialTheme.colorScheme.surface
    val background = MaterialTheme.colorScheme.background
    val fallback = MaterialTheme.colorScheme.surfaceContainer
    Canvas(Modifier.fillMaxSize()) {
        if (!frame.motion.moving) {
            cache.ready = false
            drawRect(background)
            return@Canvas
        }
        val hostSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
        val canBlur = !ExperiencePreferences.options.disableBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            layer != null && layer.size.width > 0 && layer.size.height > 0
        if (canBlur && (!cache.ready || cache.size != hostSize)) {
            val sampleScale = playerGlassSampleScale(hostSize.width, hostSize.height)
            val radius = 24.dp.toPx() * sampleScale
            blurred.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
            blurred.record(size = IntSize(
                (size.width * sampleScale).roundToInt().coerceAtLeast(1),
                (size.height * sampleScale).roundToInt().coerceAtLeast(1),
            )) {
                drawRect(fallback)
                scale(sampleScale, sampleScale, Offset.Zero) {
                    translate(sourceBounds.left - frame.motion.hostBounds.left,
                        sourceBounds.top - frame.motion.hostBounds.top) { drawLayer(layer) }
                }
            }
            cache.size = hostSize
            cache.scale = sampleScale
            cache.ready = true
        }
        if (canBlur && cache.ready) {
            scale(1f / cache.scale, 1f / cache.scale, Offset.Zero) { drawLayer(blurred) }
            // 源端与歌曲菜单的 24dp 模糊、72% 底色一致，终点由原菜单快照接管。
            drawRect(surface.copy(alpha = motionLerp(.72f, .16f, frame.motion.value)))
        } else drawRect(fallback)
        if (frame.target is EntityTarget.Album) {
            // 信息区延后挂载时，用封面下缘的同色渐变承接；底层玻璃仍持续保留。
            val finish = ((frame.motion.value - .7f) / .3f).coerceIn(0f, 1f)
            drawRect(background.copy(alpha = finish * finish * (3f - 2f * finish)))
            val coverSide = size.width * if (usesTabletLandscape(size.width.toDp(), size.height.toDp())) .43f else 1f
            val cover = (frame.motion.targetSnapshot[frame.motion.coverKey]
                ?: frame.motion.targets[frame.motion.coverKey])?.bounds
                ?.translate(-frame.motion.hostBounds.left, -frame.motion.hostBounds.top)
                ?: Rect(0f, 0f, coverSide, coverSide)
            val bottom = cover.bottom
            val height = cover.height
            val reveal = ((frame.motion.value - .08f) / .62f).coerceIn(0f, 1f)
            val alpha = reveal * reveal * (3f - 2f * reveal)
            drawRect(Brush.verticalGradient(
                listOf(background.copy(alpha = 0f), background.copy(alpha = alpha)),
                startY = bottom - height * .35f,
                endY = bottom.coerceAtLeast(1f),
            ))
            if (cover.right < size.width * .7f) {
                // 平板横屏的歌曲区位于封面右侧，使用相同底色向右过渡。
                drawRect(Brush.horizontalGradient(
                    listOf(background.copy(alpha = 0f), background.copy(alpha = alpha)),
                    startX = cover.right - 32.dp.toPx(), endX = cover.right + 32.dp.toPx(),
                ))
            }
        }
    }
}
