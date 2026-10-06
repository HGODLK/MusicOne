package com.musicone.demo

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** 收起末段逐渐接回首页按钮的玻璃边框，避免端点突然亮起。 */
internal fun Modifier.qqSearchBorder(
    geometry: () -> QqSearchGeometry,
    progress: () -> Float,
    color: Color,
) = drawWithContent {
    drawContent()
    val alpha = (1f - progress()).coerceIn(0f, 1f)
    if (alpha > 0f) {
        val frame = geometry()
        val stroke = .75.dp.toPx()
        val inset = stroke / 2f
        val width = frame.width.toPx()
        val height = frame.height.toPx()
        val radius = minOf(frame.corner.toPx(), width / 2f, height / 2f) - inset
        drawRoundRect(
            color = color.copy(alpha = color.alpha * alpha),
            topLeft = Offset(size.width - frame.right.toPx() - width + inset, frame.top.toPx() + inset),
            size = Size((width - stroke).coerceAtLeast(0f), (height - stroke).coerceAtLeast(0f)),
            cornerRadius = CornerRadius(radius.coerceAtLeast(0f)),
            style = Stroke(stroke),
        )
    }
}
