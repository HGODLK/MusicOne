package com.musicone

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates

/** 歌词视口几何键（方向 + 宽高），仅在真实尺寸或方向变化时触发缓存失效。 */
internal data class LyricViewportGeometry(val rotation: Int, val width: Int, val height: Int)

internal fun lyricClipCovers(expected: Rect, actual: Rect, tolerance: Float = 1f): Boolean =
    expected.isEmpty || (!actual.isEmpty && actual.left <= expected.left + tolerance &&
        actual.top <= expected.top + tolerance && actual.right >= expected.right - tolerance &&
        actual.bottom >= expected.bottom - tolerance)

/** 校验文字区域是否确实落在列表视口内，防止仅凭绘制回调误判为可见就绪。 */
internal fun lyricTextClipReady(viewport: LayoutCoordinates?, text: LayoutCoordinates?): Boolean {
    if (viewport?.isAttached != true || text?.isAttached != true) return false
    return runCatching {
        var root: LayoutCoordinates = viewport
        while (true) root = root.parentLayoutCoordinates ?: break
        val screen = Rect(0f, 0f, root.size.width.toFloat(), root.size.height.toFloat())
        val expected = root.localBoundingBoxOf(text, clipBounds = false)
            .intersect(root.localBoundingBoxOf(viewport, clipBounds = false)).intersect(screen)
        lyricClipCovers(expected, root.localBoundingBoxOf(text, clipBounds = true))
    }.getOrDefault(false)
}
