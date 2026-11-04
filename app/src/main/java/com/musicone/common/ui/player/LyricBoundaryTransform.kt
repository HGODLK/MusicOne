package com.musicone

import androidx.compose.ui.geometry.Offset

internal class LyricLineVisualTransform(val pivot: () -> Offset, val scale: () -> Float,
    val offset: () -> Float)

/** 放置时去掉行强调补偿，动画帧按同一公式重放，避免逐帧读取父子矩阵。 */
internal fun lyricNativeTextOrigin(drawn: Offset, pivot: Offset, scale: Float, offset: Float): Offset =
    pivot + (drawn - pivot - Offset(0f, offset)) / scale.coerceAtLeast(.001f)

internal fun lyricDrawnTextOrigin(native: Offset, pivot: Offset, scale: Float, offset: Float): Offset =
    pivot + (native - pivot) * scale + Offset(0f, offset)
