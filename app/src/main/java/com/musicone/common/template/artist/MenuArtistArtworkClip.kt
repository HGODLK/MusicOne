package com.musicone

import androidx.compose.ui.geometry.Rect

/** 把裁剪后的可见区域换算成头像本地坐标，避免把被裁掉一半的尺寸错当成头像原尺寸。 */
internal fun menuArtistLocalClip(bounds: Rect, visibleBounds: Rect): Rect {
    val visible = bounds.intersect(visibleBounds)
    if (visible.isEmpty) return Rect.Zero
    return visible.translate(-bounds.left, -bounds.top)
}
