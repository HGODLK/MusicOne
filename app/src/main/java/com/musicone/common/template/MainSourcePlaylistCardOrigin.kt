package com.musicone

import androidx.compose.ui.geometry.Rect

/** 长按编辑歌单卡片时记下卡片位置和当前封面快照，交给浮层无缝衔接。 */
internal data class MainSourcePlaylistCardOrigin(
    val bounds: Rect,
    val artwork: List<ArtworkBlendSnapshot>?,
    val currentBounds: () -> Rect = { bounds },
)
