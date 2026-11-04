package com.musicone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

internal fun playerRevealGeometry(motion: PageMotion, flight: PhonePlayerFlightRotation?, rotation: Int): PhonePlayerFlightGeometry {
    val target = motion.targetSnapshot["surface"]?.bounds ?: motion.targets["surface"]?.bounds
    val source = playerSourceAnchor(motion, "surface")?.bounds
    if (!motion.moving || source == null || target == null)
        return PhonePlayerFlightGeometry(Rect(0f, 0f, motion.hostBounds.width, motion.hostBounds.height), 0f)
    val geometry = phonePlayerFlightGeometry(motion, flight, rotation, "surface", source, target)
    return geometry.copy(bounds = geometry.bounds.translate(-target.left, -target.top))
}

/** 圆角始终交给原生轮廓；外层旋转遮罩，内层等量反转，使背景与内容留在物理窗口。 */
@Composable
internal fun PlayerSurfaceReveal(motion: PageMotion, prewarming: Boolean, content: @Composable () -> Unit) {
    val flight = LocalPhonePlayerFlightRotation.current
    val rotation = LocalPhonePlayerRotation.current
    val sharedFrame = LocalPlayerSurfaceFlightFrame.current
    val reveal = remember(motion, flight, rotation, sharedFrame) { derivedStateOf {
        val frame = sharedFrame?.value
        if (motion.moving && frame != null)
            frame.geometry.copy(bounds = frame.geometry.bounds.translate(-frame.targetBounds.left, -frame.targetBounds.top))
        else playerRevealGeometry(motion, flight, rotation)
    } }
    val shape = remember(motion, flight, rotation, reveal) { PlayerRevealShape(motion, flight, rotation, reveal) }
    val rotated = flight != null && (rotation != 0 || flight.handoffActive)
    val modifier = when {
        prewarming -> Modifier
        motion.phase == MotionPhase.PREPARING -> Modifier.graphicsLayer { alpha = 0f }
        motion.moving -> Modifier.graphicsLayer {
            alpha = playerSurfaceReveal(motion.value)
            if (!rotated) { this.shape = shape; clip = true }
        }
        else -> Modifier.graphicsLayer { alpha = if (motion.mounted) 1f else 0f }
    }
    Box(Modifier.fillMaxSize().then(modifier)) {
        Box(Modifier.fillMaxSize().then(if (rotated)
            Modifier.playerSurfaceClipLayer(motion, reveal, PlayerOpeningLayer.BODY) else Modifier)) {
            // 固定引用节点，内容更新原显示列表；不能让遮罩角度触发正文全局坐标重新登记。
            Box(Modifier.fillMaxSize().then(if (rotated) Modifier.graphicsLayer() else Modifier)) { content() }
        }
    }
}

internal class PlayerRevealShape(private val motion: PageMotion,
    private val flight: PhonePlayerFlightRotation? = null, private val rotation: Int = 0,
    private val reveal: State<PhonePlayerFlightGeometry>? = null) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val bounds = if (motion.moving) (reveal?.value ?: playerRevealGeometry(motion, flight, rotation)).bounds
            else Rect(0f, 0f, size.width, size.height)
        val corner = CornerRadius(with(density) { playerSurfaceCorner(motion.value).dp.toPx() })
        return Outline.Rounded(RoundRect(bounds, corner, corner, corner, corner))
    }
}
