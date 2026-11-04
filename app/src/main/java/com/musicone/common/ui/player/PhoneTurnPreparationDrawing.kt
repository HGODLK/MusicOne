package com.musicone

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas

/** 隐藏目标仍完成绘制；旧帧含透明像素时也必须完整替换，避免透出临时排版。 */
internal fun DrawScope.drawPhoneTurnPreparedFrame(source: ImageBitmap?, sourceAlpha: Float,
    preparing: Boolean, isolationPaint: Paint, drawTarget: DrawScope.() -> Unit) {
    if (preparing && source != null) {
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(0f, 0f, size.width, size.height), isolationPaint)
            try {
                drawTarget()
                drawImage(source, blendMode = BlendMode.Src)
            } finally { canvas.restore() }
        }
    } else {
        drawTarget()
        source?.let { drawImage(it, alpha = sourceAlpha) }
    }
}
