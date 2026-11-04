package com.musicone

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates

/** 沿真实父子矩阵换算，避开内部旋转后仍可能保留旧方向的根偏移快速缓存。 */
internal fun LayoutCoordinates.playerPointInRoot(point: Offset): Offset {
    var root = this
    while (true) root = root.parentLayoutCoordinates ?: break
    return root.localPositionOf(this, point)
}
