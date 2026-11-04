package com.musicone

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import kotlin.math.atan2

/** 并列读取快捷根坐标与父子矩阵路径，仅作诊断，不将结果写回动画。 */
internal fun phoneCoordinateDiagnostic(coordinates: LayoutCoordinates): String {
    if (!coordinates.isAttached) return "detached"
    var root = coordinates
    while (true) root = root.parentLayoutCoordinates ?: break
    val origin = Offset.Zero
    val edge = Offset(coordinates.size.width.toFloat(), 0f)
    val center = Offset(coordinates.size.width / 2f, coordinates.size.height / 2f)
    val fastOrigin = coordinates.localToRoot(origin)
    val fastAxis = coordinates.localToRoot(edge) - fastOrigin
    val matrixOrigin = root.localPositionOf(coordinates, origin)
    val matrixAxis = root.localPositionOf(coordinates, edge) - matrixOrigin
    fun angle(axis: Offset) = atan2(axis.y, axis.x) * 180f / Math.PI.toFloat()
    return "size=${coordinates.size} fastOrigin=$fastOrigin fastAngle=${angle(fastAxis)} " +
        "fastCenter=${coordinates.localToRoot(center)} matrixOrigin=$matrixOrigin " +
        "matrixAngle=${angle(matrixAxis)} matrixCenter=${root.localPositionOf(coordinates, center)}"
}
