package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/** 未提供自定义页面的主音源共用同一份收藏、歌单和本地库布局。 */
@Composable
internal fun PlatformMyScreen(
    account: MusicAccount?,
    library: MainSourceLibraryUiState,
    bottomInset: Dp,
    onOpenSettings: () -> Unit,
    onOpenPlaylist: (MusicPlaylist) -> Unit,
    onRefreshLibrary: () -> Unit,
    onPlayPlaylist: (MusicPlaylist) -> Unit,
    source: MusicSource? = null,
) {
    MainSourceMyScreen(account, library, bottomInset, onOpenSettings, onOpenPlaylist,
        onRefreshLibrary, onPlayPlaylist, source)
}
