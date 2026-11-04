package com.musicone

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** 背景保留原轨迹；子元素在背景局部坐标内插，避免独立屏幕直线在旋转中穿出窄条。 */
internal fun playerFlightContentGeometry(motion: PageMotion, flight: PhonePlayerFlightRotation?,
    rotation: Int, key: String, source: Rect, destination: Rect,
    original: PhonePlayerFlightGeometry, surfaceFrame: PlayerSurfaceFlightFrame? = null): PhonePlayerFlightGeometry {
    if (key != "cover" && key != "title" && key != "subtitle") return original
    val cached = surfaceFrame?.contentGeometry
    val sourceSurface = cached?.sourceBounds ?: playerSourceAnchor(motion, "surface")?.bounds ?: return original
    val targetSurface = motion.targetSnapshot["surface"]?.bounds ?: return original
    val start = cached?.sourceGeometry ?: phonePlayerFlightSource(motion, flight, rotation, "surface", sourceSurface)
    val surface = cached?.geometry ?: phonePlayerFlightGeometry(motion, flight, rotation, "surface", sourceSurface, targetSurface)
    val offset = source.center - sourceSurface.center
    val sourceOffset = Offset(offset.x * start.bounds.width / sourceSurface.width.coerceAtLeast(1f),
        offset.y * start.bounds.height / sourceSurface.height.coerceAtLeast(1f))
    val targetOffset = if (cached != null) cached.inverseTargetRotation.rotate(destination.center - cached.targetCenter)
    else {
        val live = flight?.targetPose("cover")?.let { phonePlayerFlightSurfacePose(motion.hostBounds, it.rotation) }
        val targetCenter = live?.pivot ?: targetSurface.center
        val targetRotation = live?.rotation ?: if (flight != null) rotation.toFloat() else 0f
        phonePlayerFlightRotate(destination.center - targetCenter, Offset.Zero, -targetRotation)
    }
    val local = Offset(motionLerp(sourceOffset.x, targetOffset.x, motion.value),
        motionLerp(sourceOffset.y, targetOffset.y, motion.value))
    val center = surface.bounds.center + (cached?.rotation?.rotate(local)
        ?: phonePlayerFlightRotate(local, Offset.Zero, surface.rotation))
    return original.copy(bounds = Rect(center - Offset(original.bounds.width / 2f, original.bounds.height / 2f),
        original.bounds.size))
}
