package com.musicone.demo

import androidx.compose.animation.Animatable
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal class MiniPlayerInk {
    var bounds = Rect.Zero
    val regions = mutableMapOf<String, Rect>()
    val light = mutableStateMapOf<String, Boolean>()
    val candidates = mutableMapOf<String, Pair<Boolean, Int>>()
}

private val LocalMiniPlayerInk = staticCompositionLocalOf<MiniPlayerInk?> { null }
internal val LocalMiniPlayerImmersiveVisual = staticCompositionLocalOf<NeteaseProfileVisual?> { null }

/** 只读取迷你播放器下的页面，不读取前景文字或搜索菜单；滚动后继续更新同一小块采样。 */
@Composable
internal fun MiniPlayerInkProvider(backdrop: GraphicsLayer, backdropBounds: Rect,
    content: @Composable (MiniPlayerInk) -> Unit) {
    val ink = remember { MiniPlayerInk() }
    val dark = androidx.compose.material3.MaterialTheme.colorScheme.background.luminance() < .3f
    val capture = rememberGraphicsLayer()
    val sourceBounds by rememberUpdatedState(backdropBounds)
    val motion = LocalPlayerMotion.current
    val searchMotion = LocalQqSearchMotion.current
    val pageInteraction = LocalPageInteraction.current
    val entityNavigation = LocalEntityNavigation.current
    val playlistMotion = LocalPlaylistMotion.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(backdrop, capture, lifecycle, density, direction, dark) {
        if (dark) {
            ink.candidates.clear()
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var nextSampleAt = 0L
            while (isActive) {
                delay(40)
                val bounds = ink.bounds
                if (bounds.isEmpty || sourceBounds.isEmpty || motion?.mounted == true ||
                    searchMotion?.fullMoving == true || entityNavigation?.moving == true ||
                    playlistMotion?.moving == true || pageInteraction?.quiet() == false
                ) {
                    ink.candidates.clear()
                    nextSampleAt = 0L
                    continue
                }
                if (SystemClock.uptimeMillis() < nextSampleAt) continue
                try {
                    val width = minOf(384, bounds.width.toInt()).coerceAtLeast(1)
                    val height = 24
                    val radius = with(density) { 18.dp.toPx() }
                    capture.renderEffect = if (ExperiencePreferences.options.disableBlur) null else
                        BlurEffect(radius * width / bounds.width, radius * height / bounds.height, TileMode.Clamp)
                    capture.record(density, direction, IntSize(width, height)) {
                        drawRect(Color(0xFFF6F7F8))
                        scale(width / bounds.width, height / bounds.height, Offset.Zero) {
                            translate(sourceBounds.left - bounds.left, sourceBounds.top - bounds.top) {
                                drawLayer(backdrop)
                            }
                        }
                    }
                    val pixels = capture.toImageBitmap().toPixelMap()
                    // 截图会挂起；恢复时若已开始新的手势或惯性滚动，丢弃旧背景结果。
                    if (pageInteraction?.quiet() == false) {
                        ink.candidates.clear()
                        nextSampleAt = 0L
                        continue
                    }
                    var confirmed = ink.regions.isNotEmpty()
                    ink.regions.toMap().forEach { (key, region) ->
                        val crop = region.intersect(bounds)
                        if (!crop.isEmpty) {
                            // 每行文字独立取样，避免封面、空白边距和相邻按钮改变该行字色。
                            val lightness = ArrayList<Float>(24)
                            repeat(24) { index ->
                                val x = crop.left + crop.width * ((index % 8 + .5f) / 8f)
                                val y = crop.top + crop.height * ((index / 8 + .5f) / 3f)
                                val pixel = pixels[
                                    ((x - bounds.left) / bounds.width * width).toInt().coerceIn(0, width - 1),
                                    ((y - bounds.top) / bounds.height * height).toInt().coerceIn(0, height - 1),
                                ]
                                val surface = if (ExperiencePreferences.options.disableBlur || android.os.Build.VERSION.SDK_INT < 31) {
                                    Color(0xFFF6F7F8)
                                } else {
                                    Color.White.copy(alpha = .28f).compositeOver(pixel)
                                }
                                lightness += surface.luminance()
                            }
                            val candidate = miniPlayerRegionUsesLightInk(lightness, ink.light[key] == true)
                            val previous = ink.candidates[key]
                            val count = if (previous?.first == candidate) previous.second + 1 else 1
                            ink.candidates[key] = candidate to count
                            confirmed = confirmed && count >= 2
                            if (count >= 2 && pageInteraction?.quiet() != false) ink.light[key] = candidate
                        }
                    }
                    // 保留两次确认；稳定后降低离屏截图频率，异步封面到达仍能重新取色。
                    nextSampleAt = SystemClock.uptimeMillis() + if (confirmed) 200L else 40L
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // 图层尚未绘制或设备暂不可读时保留上一帧字色，不闪回默认主题。
                }
            }
        }
    }
    CompositionLocalProvider(LocalMiniPlayerInk provides ink) { content(ink) }
}

/** 明暗切换保留小幅迟滞，避免滚动纹理恰好经过阈值时反复闪动。 */
internal fun miniPlayerUsesLightInk(luminance: Float, wasLight: Boolean): Boolean =
    luminance < if (wasLight) .205f else .155f

/** 比较偏差较大的四分位区域，防止一小块极亮或极暗封面拖偏整行平均值。 */
internal fun miniPlayerRegionUsesLightInk(samples: List<Float>, wasLight: Boolean): Boolean {
    if (samples.isEmpty()) return wasLight
    val sorted = samples.sorted()
    val darkContrast = (sorted[sorted.size / 4] + .05f) / .05f
    val lightContrast = 1.05f / (sorted[sorted.size * 3 / 4] + .05f)
    return if (wasLight) lightContrast * 1.15f >= darkContrast else lightContrast > darkContrast * 1.15f
}

@Composable
internal fun miniPlayerInkColor(key: String): Color {
    val handoff = LocalPlayerControlHandoff.current
    val immersiveVisual = LocalMiniPlayerImmersiveVisual.current
    if (immersiveVisual != null) {
        val fixed = if (key == "artist") immersiveVisual.secondary else immersiveVisual.foreground
        SideEffect { handoff?.observeInk(key, fixed) }
        return fixed
    }
    val dark = androidx.compose.material3.MaterialTheme.colorScheme.background.luminance() < .3f
    val light = LocalMiniPlayerInk.current?.light?.get(key) == true
    val pressed = LocalPageInteraction.current?.pressed == true
    val moving = LocalPlayerMotion.current?.mounted == true
    val target = if (dark || light) Color.White else Color.Black
    val color = remember { Animatable(target) }
    LaunchedEffect(target, dark, pressed, moving) {
        if (dark || pressed || moving) color.snapTo(target)
        else color.animateTo(target, musicMotion(160))
    }
    SideEffect { handoff?.observeInk(key, color.value) }
    return color.value
}

@Composable
internal fun Modifier.miniPlayerInkRegion(key: String): Modifier {
    val ink = LocalMiniPlayerInk.current ?: return this
    return onGloballyPositioned { ink.regions[key] = it.boundsInRoot() }
}
