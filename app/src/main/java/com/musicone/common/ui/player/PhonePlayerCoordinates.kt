package com.musicone

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates

/** 手动沿父子节点向上做坐标变换，绕开 Compose 内部旋转后根偏移缓存可能滞后的问题。 */
internal fun LayoutCoordinates.playerPointInRoot(point: Offset): Offset {
    var root = this
    while (true) root = root.parentLayoutCoordinates ?: break
    return root.localPositionOf(this, point)
}
