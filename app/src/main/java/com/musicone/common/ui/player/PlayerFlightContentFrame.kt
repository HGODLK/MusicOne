package com.musicone

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.cos
import kotlin.math.sin

/** 同一帧共用系数，保留原旋转函数的浮点运算顺序。 */
internal data class PlayerFlightRotation(val cosine: Float, val sine: Float) {
    fun rotate(point: Offset): Offset {
        val center = Offset.Zero
        val v = point - center
        return center + Offset(v.x * cosine - v.y * sine, v.x * sine + v.y * cosine)
    }
}

internal fun playerFlightRotation(angle: Float): PlayerFlightRotation {
    val radians = angle * Math.PI / 180.0
    return PlayerFlightRotation(cos(radians).toFloat(), sin(radians).toFloat())
}

/** 只保存本帧公共背景的计算结果，不缓存封面、文字自身的实时端点。 */
internal data class PlayerFlightContentFrame(
    val sourceBounds: Rect,
    val sourceGeometry: PhonePlayerFlightGeometry,
    val geometry: PhonePlayerFlightGeometry,
    val targetCenter: Offset,
    val inverseTargetRotation: PlayerFlightRotation,
    val rotation: PlayerFlightRotation,
)

internal fun playerFlightContentFrame(motion: PageMotion, flight: PhonePlayerFlightRotation?, rotation: Int,
    sourceBounds: Rect, sourceGeometry: PhonePlayerFlightGeometry,
    geometry: PhonePlayerFlightGeometry): PlayerFlightContentFrame? {
    if (flight == null || rotation == 0 && !flight.handoffActive) return null
    val target = motion.targetSnapshot["surface"]?.bounds ?: return null
    val live = flight.targetPose("cover")?.let { phonePlayerFlightSurfacePose(motion.hostBounds, it.rotation) }
    val targetRotation = live?.rotation ?: rotation.toFloat()
    return PlayerFlightContentFrame(sourceBounds, sourceGeometry, geometry,
        live?.pivot ?: target.center, playerFlightRotation(-targetRotation), playerFlightRotation(geometry.rotation))
}
