package com.musicone

import androidx.compose.ui.geometry.Offset
import kotlin.math.cos
import kotlin.math.sin

/** 按初始总距离和当前剩余位移计算转向完成度（带 smoothstep 缓动），避免分母变化引起进度倒退。 */
internal fun phoneTurnDrawingProgress(distance: Float, remaining: Float): Float {
    val progress = if (distance <= .5f) 1f else (1f - remaining / distance).coerceIn(0f, 1f)
    return progress * progress * (3f - 2f * progress)
}

/** 更换旋转枢轴比例时，同步换算等价的新枢轴坐标，保证当前画面位置不动。 */
internal fun phoneTurnReanchor(pose: PhoneTurnPose, fraction: Offset): PhoneTurnPose {
    val local = Offset(pose.size.width * (fraction.x - pose.pivotFraction.x),
        pose.size.height * (fraction.y - pose.pivotFraction.y))
    val radians = pose.rotation * Math.PI / 180.0
    val shift = Offset(local.x * cos(radians).toFloat() - local.y * sin(radians).toFloat(),
        local.x * sin(radians).toFloat() + local.y * cos(radians).toFloat())
    return pose.copy(pivot = pose.pivot + shift, pivotFraction = fraction)
}

/** 换枢轴时一并换算线速度（叠加缩放和角速度带来的切向速度），保持运动连续。 */
internal fun phoneTurnReanchorVelocity(pose: PhoneTurnPose, fraction: Offset, velocity: Offset,
    extentVelocity: Offset, angularVelocity: Float): Offset {
    val delta = fraction - pose.pivotFraction
    val extent = Offset(extentVelocity.x * delta.x, extentVelocity.y * delta.y)
    val radians = pose.rotation * Math.PI / 180.0
    val shift = phoneTurnReanchor(pose, fraction).pivot - pose.pivot
    val angular = angularVelocity * Math.PI.toFloat() / 180f
    return velocity + Offset(extent.x * cos(radians).toFloat() - extent.y * sin(radians).toFloat(),
        extent.x * sin(radians).toFloat() + extent.y * cos(radians).toFloat()) +
        Offset(-shift.y * angular, shift.x * angular)
}
