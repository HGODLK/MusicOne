package com.musicone.demo

import androidx.compose.ui.geometry.Rect

/** 编辑浮层同时接管完整布局位置和当前封面混合画面。 */
internal data class QqPlaylistCardOrigin(
    val bounds: Rect,
    val artwork: List<ArtworkBlendSnapshot>?,
    val currentBounds: () -> Rect = { bounds },
)
