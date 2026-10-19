package com.musicone.demo

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

internal data class PlatformHomeActions(
    val onOpenRecommendation: (MusicPlaylist) -> Unit,
    val onOpenLoadedPlaylist: (MusicPlaylist) -> Unit,
    val onPlayRecommendation: (MusicPlaylist) -> Unit,
    val onRefreshRecommendations: () -> Unit,
    val onRefreshRecommendedTracks: (Boolean) -> Unit,
)

@Composable
internal fun PlatformHomeScreen(
    source: MusicSource,
    state: MusicOneUiState,
    catalog: MusicCatalogUiState,
    viewModel: MusicPlaybackRuntime,
    sessionRevision: Long,
    sessionStatus: SessionStatus,
    bottomInset: Dp,
    onChooseSource: () -> Unit,
    onOpenRecommendation: (MusicPlaylist) -> Unit,
    onOpenLoadedPlaylist: (MusicPlaylist) -> Unit,
    onPlayRecommendation: (MusicPlaylist) -> Unit,
    onRefreshRecommendations: () -> Unit,
    onRefreshRecommendedTracks: (Boolean) -> Unit,
) {
    if (sessionStatus != SessionStatus.CONNECTED) {
        PlatformSignedOutScreen(
            MusicOnePage.HOME,
            source,
            sessionStatus == SessionStatus.CHECKING,
            bottomInset,
            onChooseSource,
        )
        return
    }
    val actions = PlatformHomeActions(
        onOpenRecommendation,
        onOpenLoadedPlaylist,
        onPlayRecommendation,
        onRefreshRecommendations,
        onRefreshRecommendedTracks,
    )
    CompositionLocalProvider(LocalTrackAccessBadgesVisible provides false) {
        when (source) {
            MusicSource.NETEASE -> NeteaseHomeScreen(state, catalog, viewModel, bottomInset, actions)
            MusicSource.QQ -> QqHomeScreen(state, catalog, viewModel, sessionRevision, bottomInset, actions)
            MusicSource.KUGOU -> KugouHomeScreen(state, catalog, viewModel, bottomInset, actions)
            MusicSource.LOCAL, MusicSource.BILIBILI -> Unit
        }
    }
}
