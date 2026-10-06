package com.musicone.demo

import androidx.compose.material3.MaterialTheme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.*

/** 固定终点排版，仅在绘制阶段裁剪表面与位移文字；来源保存像素而非实时引用。 */
@Composable
internal fun ExpandingPageSurface(origin: Rect, progress: () -> Float, snapshot: ImageBitmap? = null,
    translateContent: Boolean = true,
    surfaceReveal: (Float) -> Float = { 1f },
    contentReveal: (Float) -> Float = { ((it - .18f) / .82f).coerceIn(0f, 1f) },
    sourceCorner: Dp = 28.dp,
    sourceContent: (@Composable () -> Unit)? = null,
    backgroundContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val shape = remember(origin, sourceCorner) { ExpandingPageShape(origin, { bounds }, progress, sourceCorner) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInRoot() }
        .graphicsLayer {
            val p = progress()
            this.shape = shape
            clip = true
            alpha = if (origin.isEmpty) p else surfaceReveal(p)
        }
        .then(if (backgroundContent == null) Modifier.background(MaterialTheme.colorScheme.background) else Modifier)
        .clickable(remember { MutableInteractionSource() }, null) {}) {
        backgroundContent?.invoke()
        Box(Modifier.fillMaxSize().graphicsLayer {
            val p = progress()
            alpha = contentReveal(p)
            translationY = if (!translateContent || ExperiencePreferences.options.reduceMotion) 0f else 28.dp.toPx() * (1f - p)
        }) { content() }
        if (sourceContent != null && !origin.isEmpty) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            Box(Modifier.size(with(density) { origin.width.toDp() }, with(density) { origin.height.toDp() })
                .graphicsLayer {
                    val p = progress()
                    translationX = origin.left - bounds.left
                    translationY = origin.top - bounds.top - 20.dp.toPx() * p
                    alpha = (1f - p / .45f).coerceIn(0f, 1f)
                }, contentAlignment = androidx.compose.ui.Alignment.Center) { sourceContent() }
        }
        if (snapshot != null) Canvas(Modifier.fillMaxSize()) {
            val p = progress()
            val source = origin.translate(-bounds.left, -bounds.top)
            drawImage(snapshot, dstOffset = IntOffset(source.left.toInt(), (source.top - 20.dp.toPx() * p).toInt()),
                dstSize = IntSize(source.width.toInt().coerceAtLeast(1), source.height.toInt().coerceAtLeast(1)),
                alpha = (1f - p / .45f).coerceIn(0f, 1f))
        }
    }
}

private class ExpandingPageShape(
    val origin: Rect,
    val bounds: () -> Rect,
    val progress: () -> Float,
    val sourceCorner: Dp,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val p = progress()
        val host = bounds()
        val rect = if (origin.isEmpty || ExperiencePreferences.options.reduceMotion) Rect(Offset.Zero, size)
            else lerp(origin.translate(-host.left, -host.top), Rect(Offset.Zero, size), p)
        return Outline.Rounded(RoundRect(rect, CornerRadius(with(density) { sourceCorner.toPx() } * (1f - p))))
    }
}
