package com.musicone

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp

/** 保留原离屏及源入混合，只改原生图层滤镜，不因颜色进度重录文字、图标。 */
internal fun Modifier.playerFlightTint(from: Color, to: Color, motion: PageMotion): Modifier = graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
    colorFilter = playerFlightTintFilter(from, to, motion.value)
}

internal fun playerFlightTintFilter(from: Color, to: Color, progress: Float): ColorFilter =
    ColorFilter.tint(lerp(from, to, playerMaterialProgress(progress)), BlendMode.SrcIn)
