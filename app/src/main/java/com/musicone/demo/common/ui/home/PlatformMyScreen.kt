package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

@Composable
internal fun PlatformMyScreen(
    source: MusicSource,
    account: MusicAccount?,
    sessionStatus: SessionStatus,
    catalog: MusicCatalogUiState,
    neteaseVisual: NeteaseProfileVisual,
    qqLibrary: QqLibraryUiState,
    kugouLibrary: KugouLibraryUiState,
    bottomInset: Dp,
    onOpenSettings: () -> Unit,
    onOpenPlaylist: (MusicPlaylist) -> Unit,
    onRefreshNeteaseLibrary: () -> Unit,
    hasCustomNeteaseBackground: Boolean,
    onChooseCustomNeteaseBackground: () -> Unit,
    onRestoreDefaultNeteaseBackground: () -> Unit,
    onRefreshQqLibrary: () -> Unit,
    onPlayQqPlaylist: (MusicPlaylist) -> Unit,
    onChooseSource: () -> Unit,
) {
    if (sessionStatus != SessionStatus.CONNECTED) {
        PlatformSignedOutScreen(
            MusicOnePage.MY,
            source,
            sessionStatus == SessionStatus.CHECKING,
            bottomInset,
            onChooseSource,
        )
        return
    }
    when (source) {
        MusicSource.NETEASE -> NeteaseMyScreen(
            account = account,
            catalog = catalog,
            visual = neteaseVisual,
            bottomInset = bottomInset,
            onOpenPlaylist = onOpenPlaylist,
            onRefreshLibrary = onRefreshNeteaseLibrary,
            hasCustomBackground = hasCustomNeteaseBackground,
            onChooseCustomBackground = onChooseCustomNeteaseBackground,
            onRestoreDefaultBackground = onRestoreDefaultNeteaseBackground,
        )
        MusicSource.QQ -> QqMyScreen(
            account,
            qqLibrary,
            bottomInset,
            onOpenSettings,
            onOpenPlaylist,
            onRefreshQqLibrary,
            onPlayQqPlaylist,
        )
        MusicSource.KUGOU -> KugouMyScreen(
            account,
            kugouLibrary,
            bottomInset,
            onOpenSettings,
            onOpenPlaylist,
            onRefreshQqLibrary,
        )
    }
}
