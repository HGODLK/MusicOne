package com.musicone

import androidx.compose.ui.geometry.Offset
import kotlin.math.cos
import kotlin.math.sin

/** 起点距离固定于本轮补偿，正文平移不能改变分母并让遮罩反向展开。 */
internal fun phoneTurnDrawingProgress(distance: Float, remaining: Float): Float {
    val progress = if (distance <= .5f) 1f else (1f - remaining / distance).coerceIn(0f, 1f)
    return progress * progress * (3f - 2f * progress)
}

/** 晚到布局更换枢轴时，先换算同一幅画面的新枢轴，不能直接挪动正在显示的内容。 */
internal fun phoneTurnReanchor(pose: PhoneTurnPose, fraction: Offset): PhoneTurnPose {
    val local = Offset(pose.size.width * (fraction.x - pose.pivotFraction.x),
        pose.size.height * (fraction.y - pose.pivotFraction.y))
    val radians = pose.rotation * Math.PI / 180.0
    val shift = Offset(local.x * cos(radians).toFloat() - local.y * sin(radians).toFloat(),
        local.x * sin(radians).toFloat() + local.y * cos(radians).toFloat())
    return pose.copy(pivot = pose.pivot + shift, pivotFraction = fraction)
}

/** 新枢轴同时继承旋转及缩放产生的切向速度，保留真实运动的连续性。 */
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
