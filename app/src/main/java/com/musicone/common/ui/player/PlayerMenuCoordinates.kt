package com.musicone

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced

/** 菜单位置与输入共用横向画布；传入的触点和避让矩形仍是物理窗口坐标。 */
internal data class PlayerMenuCoordinates(val host: Rect, val rotation: Int) {
    val localBounds: Rect get() = Rect(0f, 0f,
        if (rotation == 0) host.width else host.height,
        if (rotation == 0) host.height else host.width)

    fun pointInMenu(point: Offset): Offset {
        val relative = point - host.topLeft
        return when {
            rotation > 0 -> Offset(relative.y, host.width - relative.x)
            rotation < 0 -> Offset(host.height - relative.y, relative.x)
            else -> relative
        }
    }

    fun rectInMenu(rect: Rect): Rect {
        val a = pointInMenu(rect.topLeft)
        val b = pointInMenu(rect.bottomRight)
        return Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
    }
}

/** 先按已知方向进入菜单画布，再换算内部缩放；首帧不依赖旋转宿主的根矩阵缓存。 */
internal class PlayerMenuSampling(initial: PlayerMenuCoordinates) {
    var geometry by mutableStateOf(initial)
    var canvas by mutableStateOf<LayoutCoordinates?>(null)
    fun pointInSurface(surface: LayoutCoordinates, rootPoint: Offset): Offset? {
        val host = canvas?.takeIf { it.isAttached } ?: return null
        if (!surface.isAttached) return null
        return surface.localPositionOf(host, geometry.pointInMenu(rootPoint))
    }
}

internal val LocalPlayerMenuSampling = staticCompositionLocalOf<PlayerMenuSampling?> { null }

@Composable
internal fun PlayerMenuWindow(rotation: Int, bounds: Rect, content: @Composable BoxWithConstraintsScope.() -> Unit) {
    val sampling = remember(rotation) { PlayerMenuSampling(PlayerMenuCoordinates(bounds, rotation)) }
    SideEffect { sampling.geometry = PlayerMenuCoordinates(bounds, rotation) }
    CompositionLocalProvider(LocalPlayerMenuSampling provides sampling.takeIf { rotation != 0 }) {
        if (rotation == 0) BoxWithConstraints(Modifier.fillMaxSize(), content = content)
        else PhonePlayerWindowHost(rotation) {
            BoxWithConstraints(Modifier.fillMaxSize().onPlaced { sampling.canvas = it }, content = content)
        }
    }
}
