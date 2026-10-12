package com.musicone.demo

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal object MiniPlayerLayoutMetrics {
    val artwork = 48.dp
    val innerPadding = 7.dp
    val outerPadding = 8.dp
    val textGap = 3.dp
}

internal fun miniPlayerReservedHeightPx(artwork: Int, title: Int, artist: Int,
    textGap: Int, innerPadding: Int, outerPadding: Int): Int =
    maxOf(artwork, title + artist + textGap) + 2 * (innerPadding + outerPadding)

/** 左栏始终预留最终播放器行高，显隐动画的中间测量不参与封面预算。 */
@Composable
internal fun stablePlaylistCoverBottomInset(): Dp {
    val density = LocalDensity.current
    val miniHeight = with(density) {
        miniPlayerReservedHeightPx(MiniPlayerLayoutMetrics.artwork.roundToPx(),
            MusicOneTextStyles.miniPlayerTitle.lineHeight.roundToPx(),
            MusicOneTextStyles.miniPlayerArtist.lineHeight.roundToPx(),
            MiniPlayerLayoutMetrics.textGap.roundToPx(),
            MiniPlayerLayoutMetrics.innerPadding.roundToPx(),
            MiniPlayerLayoutMetrics.outerPadding.roundToPx()).toDp()
    }
    return miniHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
}
