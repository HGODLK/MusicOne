package com.musicone

import androidx.compose.ui.geometry.Rect

/** 完整头像只用于尺寸与共享转场，当前可见区域单独换算，不能把裁剪后的尺寸当作头像尺寸。 */
internal fun menuArtistLocalClip(bounds: Rect, visibleBounds: Rect): Rect {
    val visible = bounds.intersect(visibleBounds)
    if (visible.isEmpty) return Rect.Zero
    return visible.translate(-bounds.left, -bounds.top)
}
