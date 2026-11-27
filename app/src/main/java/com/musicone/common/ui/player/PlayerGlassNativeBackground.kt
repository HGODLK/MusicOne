package com.musicone

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

internal data class PlayerGlassNativeFrame(
    val bounds: Rect,
    val rotation: Float,
    val corner: Float,
    val progress: Float,
    val alpha: Float,
)

internal fun playerGlassCanPrepare(source: GraphicsLayer?, target: GraphicsLayer?): Boolean =
    !ExperiencePreferences.options.disableBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        source != null && target != null && source.size.width > 0 && source.size.height > 0 &&
        target.size.width > 0 && target.size.height > 0

/** 计算横屏原生毛玻璃背景层本帧的几何参数；不满足原生绘制条件时返回 null，交回普通 Canvas 绘制。 */
internal fun playerGlassNativeFrameState(motion: PageMotion, flight: PhonePlayerFlightRotation?, rotation: Int,
    prewarming: Boolean, surface: State<PlayerSurfaceFlightFrame?>, density: Density,
    ready: () -> Boolean): State<PlayerGlassNativeFrame?> = derivedStateOf(structuralEqualityPolicy()) {
    if (prewarming || !motion.moving || flight == null || rotation == 0 && !flight.handoffActive)
        return@derivedStateOf null
    // 先读取 motion.value 建立状态订阅，这样缓存一准备好下一帧就能切到原生路径。
    val progress = motion.value
    val alpha = playerGlassAlpha(progress)
    if (progress == 0f || alpha <= 0f || !ready() || motion.targetSnapshot["surface"] == null)
        return@derivedStateOf null
    val geometry = surface.value?.geometry ?: return@derivedStateOf null
    PlayerGlassNativeFrame(geometry.bounds.translate(-motion.hostBounds.left, -motion.hostBounds.top),
        geometry.rotation, with(density) { playerSurfaceCorner(progress).dp.toPx() }, progress, alpha)
}

/** 封装模糊背景的四个 GraphicsLayer，动画期间只改透明度、裁剪轮廓和旋转角度，不重新录制。 */
internal class PlayerGlassNativeBackground(
    private val source: GraphicsLayer,
    private val target: GraphicsLayer,
    private val fixed: GraphicsLayer,
    private val clipped: GraphicsLayer,
) {
    fun apply(frame: PlayerGlassNativeFrame, sampleScale: Float) {
        source.alpha = frame.alpha * (1f - frame.progress)
        target.alpha = frame.alpha * frame.progress
        clipped.setRoundRectOutline(frame.bounds.topLeft, frame.bounds.size, frame.corner)
        clipped.pivotOffset = frame.bounds.center
        clipped.rotationZ = frame.rotation
        fixed.pivotOffset = frame.bounds.center * sampleScale
        fixed.rotationZ = -frame.rotation
    }

    fun draw(scope: DrawScope) { scope.drawLayer(clipped) }
}

/** 原生毛玻璃背景层：在 graphicsLayer 属性阶段更新变换，Canvas 只负责画出合成层。 */
@Composable
internal fun PlayerGlassNativeBackgroundLayer(background: PlayerGlassNativeBackground,
    frame: State<PlayerGlassNativeFrame?>, sampleScale: () -> Float) {
    val view = LocalView.current
    Canvas(Modifier.fillMaxSize().graphicsLayer {
        val current = frame.value
        alpha = if (current == null) 0f else 1f
        if (current != null) {
            background.apply(current, sampleScale())
            // 直接修改子 GraphicsLayer 属性不会自动触发父 View 重绘，需手动 invalidate。
            view.invalidate()
        }
    }) { background.draw(this) }
}
