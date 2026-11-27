package com.musicone

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp

/** 飞行途中用 GraphicsLayer 的 ColorFilter 动态变色，避免每帧重录文字和图标。 */
internal fun Modifier.playerFlightTint(from: Color, to: Color, motion: PageMotion): Modifier = graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
    colorFilter = playerFlightTintFilter(from, to, motion.value)
}

internal fun playerFlightTintFilter(from: Color, to: Color, progress: Float): ColorFilter =
    ColorFilter.tint(lerp(from, to, playerMaterialProgress(progress)), BlendMode.SrcIn)
