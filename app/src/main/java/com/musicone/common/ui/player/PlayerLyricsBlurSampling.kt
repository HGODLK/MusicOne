package com.musicone

import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil

internal fun lyricBackdropBlurEnabled(api: Int, disabled: Boolean, controlsDisabled: Boolean): Boolean =
    api >= 31 && !disabled && !controlsDisabled

/** 向外取整保留末端像素。 */
internal fun lyricBlurSampleSize(width: Float, height: Float): IntSize = IntSize(
    ceil(width).toInt().coerceAtLeast(1),
    ceil(height).toInt().coerceAtLeast(1),
)
