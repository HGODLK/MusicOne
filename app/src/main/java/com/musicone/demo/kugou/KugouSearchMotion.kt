package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** 酷狗搜索复用 QQ 搜索从右上角按钮展开、反向收回的同一组页面运动参数。 */
internal class KugouSearchMotion {
    val reveal = Animatable(0f)
    val mounted: Boolean get() = reveal.value > 0f
}

@Composable
internal fun rememberKugouSearchMotion(opened: Boolean): KugouSearchMotion {
    val motion = remember { KugouSearchMotion() }
    LaunchedEffect(opened) {
        motion.reveal.animateTo(
            if (opened) 1f else 0f,
            if (opened) PlayerEnterAnimation else PlayerReturnAnimation,
        )
    }
    return motion
}

internal class KugouSearchRevealShape(private val progress: () -> Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = with(density) {
        val value = progress().coerceIn(0f, 1f)
        val compact = 48.dp.toPx()
        val rightInset = 20.dp.toPx() * (1f - value)
        val top = 10.dp.toPx() * (1f - value)
        val width = compact + (size.width - compact) * value
        val height = compact + (size.height - compact) * value
        val right = size.width - rightInset
        Outline.Rounded(
            RoundRect(
                left = right - width,
                top = top,
                right = right,
                bottom = top + height,
                cornerRadius = CornerRadius(26.dp.toPx() * (1f - value)),
            ),
        )
    }
}
