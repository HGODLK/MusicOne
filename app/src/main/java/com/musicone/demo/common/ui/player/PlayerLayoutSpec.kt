package com.musicone.demo

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class PlayerLayoutMode { COMPACT_SINGLE, WIDE_SINGLE, LANDSCAPE_TWO_PANE }

internal data class PlayerLayoutSpec(
    val mode: PlayerLayoutMode,
    val horizontalPadding: Dp,
    val coverMax: Dp,
    val coverWidthFraction: Float,
    val titleSize: TextUnit,
    val artistSize: TextUnit,
    val lyricBaseSize: Float,
    val lyricCoverSize: Dp,
)

internal val CompactPlayerLayoutSpec = PlayerLayoutSpec(
    PlayerLayoutMode.COMPACT_SINGLE, 26.dp, 440.dp, 1f, 22.sp, 16.sp, 32f, 62.dp,
)

internal val LocalPlayerLayoutSpec = staticCompositionLocalOf { CompactPlayerLayoutSpec }

internal fun playerLayoutSpec(width: Dp, height: Dp): PlayerLayoutSpec = when {
    usesTabletLandscape(width, height) -> CompactPlayerLayoutSpec.copy(
        mode = PlayerLayoutMode.LANDSCAPE_TWO_PANE,
        horizontalPadding = 52.dp,
        titleSize = 30.sp,
        artistSize = 20.sp,
        lyricBaseSize = 44f,
        lyricCoverSize = 88.dp,
    )
    width >= 600.dp -> PlayerLayoutSpec(
        PlayerLayoutMode.WIDE_SINGLE, 48.dp, 640.dp, .78f, 30.sp, 20.sp, 44f, 88.dp,
    )
    else -> CompactPlayerLayoutSpec
}
