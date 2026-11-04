package com.musicone

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates

/** 仅真实视口或方向变化时更新，不能用播放帧或当前句作为裁剪缓存键。 */
internal data class LyricViewportGeometry(val rotation: Int, val width: Int, val height: Int)

internal fun lyricClipCovers(expected: Rect, actual: Rect, tolerance: Float = 1f): Boolean =
    expected.isEmpty || (!actual.isEmpty && actual.left <= expected.left + tolerance &&
        actual.top <= expected.top + tolerance && actual.right >= expected.right - tolerance &&
        actual.bottom >= expected.bottom - tolerance)

/** 准备阶段校验最终裁剪，不能用已经执行绘制回调替代真正的可见范围。 */
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
