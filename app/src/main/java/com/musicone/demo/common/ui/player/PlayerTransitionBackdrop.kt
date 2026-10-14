package com.musicone.demo

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.GraphicsLayer

internal data class PlayerTransitionBackdrop(val layer: GraphicsLayer, val bounds: Rect)

/** 只提供给播放页转场，详情页玻璃仍沿用根背景，避免图层循环采样。 */
internal val LocalPlayerTransitionBackdrop = staticCompositionLocalOf<PlayerTransitionBackdrop?> { null }

internal fun playerTransitionBackdrop(
    hasEntityPages: Boolean,
    rootLayer: GraphicsLayer,
    rootBounds: Rect,
    entitySceneLayer: GraphicsLayer,
    entitySceneBounds: Rect,
): PlayerTransitionBackdrop = if (hasEntityPages) {
    PlayerTransitionBackdrop(entitySceneLayer, entitySceneBounds)
} else {
    PlayerTransitionBackdrop(rootLayer, rootBounds)
}
