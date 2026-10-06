package com.musicone.demo

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

internal fun DrawScope.drawCoverBitmap(bitmap: ImageBitmap, bounds: Rect, alpha: Float = 1f) {
    if (bounds.width <= 0f || bounds.height <= 0f || bitmap.width <= 0 || bitmap.height <= 0) return
    val sourceRatio = bitmap.width.toFloat() / bitmap.height
    val destinationRatio = bounds.width / bounds.height
    val sourceWidth: Int
    val sourceHeight: Int
    if (sourceRatio > destinationRatio) {
        sourceHeight = bitmap.height
        sourceWidth = (sourceHeight * destinationRatio).roundToInt().coerceAtMost(bitmap.width)
    } else {
        sourceWidth = bitmap.width
        sourceHeight = (sourceWidth / destinationRatio).roundToInt().coerceAtMost(bitmap.height)
    }
    drawImage(
        image = bitmap,
        srcOffset = IntOffset((bitmap.width - sourceWidth) / 2, (bitmap.height - sourceHeight) / 2),
        srcSize = IntSize(sourceWidth, sourceHeight),
        dstOffset = IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()),
        dstSize = IntSize(bounds.width.roundToInt().coerceAtLeast(1), bounds.height.roundToInt().coerceAtLeast(1)),
        filterQuality = FilterQuality.High,
        alpha = alpha,
    )
}
