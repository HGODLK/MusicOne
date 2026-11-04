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

/** 缓存就绪是在原画布中登记的；本帧已选的路径不因该登记中途切换。 */
internal fun playerGlassNativeFrameState(motion: PageMotion, flight: PhonePlayerFlightRotation?, rotation: Int,
    prewarming: Boolean, surface: State<PlayerSurfaceFlightFrame?>, density: Density,
    ready: () -> Boolean): State<PlayerGlassNativeFrame?> = derivedStateOf(structuralEqualityPolicy()) {
    if (prewarming || !motion.moving || flight == null || rotation == 0 && !flight.handoffActive)
        return@derivedStateOf null
    // 即使缓存尚未就绪也订阅进度，下一次运动输入可以使用刚建立的原引用链。
    val progress = motion.value
    val alpha = playerGlassAlpha(progress)
    if (progress == 0f || alpha <= 0f || !ready() || motion.targetSnapshot["surface"] == null)
        return@derivedStateOf null
    val geometry = surface.value?.geometry ?: return@derivedStateOf null
    PlayerGlassNativeFrame(geometry.bounds.translate(-motion.hostBounds.left, -motion.hostBounds.top),
        geometry.rotation, with(density) { playerSurfaceCorner(progress).dp.toPx() }, progress, alpha)
}

/** 持有原四个模糊引用层，沿用原混合规则，不录制像素快照。 */
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

/** 背景指令只引用原合成层，连续参数留在属性阶段，不重录这张画布。 */
@Composable
internal fun PlayerGlassNativeBackgroundLayer(background: PlayerGlassNativeBackground,
    frame: State<PlayerGlassNativeFrame?>, sampleScale: () -> Float) {
    val view = LocalView.current
    Canvas(Modifier.fillMaxSize().graphicsLayer {
        val current = frame.value
        alpha = if (current == null) 0f else 1f
        if (current != null) {
            background.apply(current, sampleScale())
            // 手动更新原生子层需要通知宿主；本节点自身保持恒等变换。
            view.invalidate()
        }
    }) { background.draw(this) }
}
