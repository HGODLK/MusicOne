package com.musicone

import androidx.compose.ui.geometry.Offset

internal class LyricLineVisualTransform(val pivot: () -> Offset, val scale: () -> Float,
    val offset: () -> Float)

/** 放置时反算出未缩放/未偏移的基准原点，动画帧直接按同一公式正算，避免每帧遍历父子矩阵。 */
internal fun lyricNativeTextOrigin(drawn: Offset, pivot: Offset, scale: Float, offset: Float): Offset =
    pivot + (drawn - pivot - Offset(0f, offset)) / scale.coerceAtLeast(.001f)

internal fun lyricDrawnTextOrigin(native: Offset, pivot: Offset, scale: Float, offset: Float): Offset =
    pivot + (native - pivot) * scale + Offset(0f, offset)
