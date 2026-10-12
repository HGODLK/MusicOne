package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

@Composable
internal fun NeteaseHomeScreen(
    state: MusicOneUiState,
    catalog: MusicCatalogUiState,
    viewModel: MusicPlaybackRuntime,
    bottomInset: Dp,
    actions: PlatformHomeActions,
) {
    PlatformHomeLayout(
        state,
        catalog,
        viewModel,
        bottomInset,
        actions.onOpenRecommendation,
        actions.onPlayRecommendation,
        actions.onRefreshRecommendedTracks,
    )
}
