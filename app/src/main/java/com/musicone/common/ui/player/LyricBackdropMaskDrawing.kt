package com.musicone

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate

/** 单位高度的原遮罩只改变绘制矩阵，保留原分辨率和隔离合成顺序。 */
internal fun DrawScope.drawLyricBackdropMask(mask: Brush, top: Float, height: Float) {
    if (height <= 0f) return
    translate(top = top) {
        scale(1f, height, pivot = Offset.Zero) {
            drawRect(mask, size = Size(size.width, 1f), blendMode = BlendMode.DstIn)
        }
    }
}
