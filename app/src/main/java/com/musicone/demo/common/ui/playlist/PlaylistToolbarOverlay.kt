package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.GraphicsLayer

/** 将歌单工具栏状态桥接到根叠加层，避免按钮被录入自己的模糊采样源。 */
@Stable
internal class PlaylistToolbarOverlayState {
    var playlistId by mutableStateOf<String?>(null)
        private set
    var showCollection by mutableStateOf(false)
        private set
    var saved by mutableStateOf(false)
        private set
    var updating by mutableStateOf(false)
        private set

    private var backAction: () -> Unit = {}
    private var toggleSavedAction: () -> Unit = {}

    fun update(
        playlistId: String,
        showCollection: Boolean,
        saved: Boolean,
        updating: Boolean,
        onBack: () -> Unit,
        onToggleSaved: () -> Unit,
    ) {
        this.playlistId = playlistId
        this.showCollection = showCollection
        this.saved = saved
        this.updating = updating
        backAction = onBack
        toggleSavedAction = onToggleSaved
    }

    fun clear(playlistId: String) {
        if (this.playlistId != playlistId) return
        this.playlistId = null
        backAction = {}
        toggleSavedAction = {}
    }

    fun back() = backAction()

    fun toggleSaved() = toggleSavedAction()
}

@Composable
internal fun rememberPlaylistToolbarOverlayState(): PlaylistToolbarOverlayState =
    remember { PlaylistToolbarOverlayState() }

@Composable
internal fun PlaylistToolbarOverlay(
    state: PlaylistToolbarOverlayState,
    motion: PageMotion,
    backdropLayer: GraphicsLayer,
    backdropBounds: Rect,
    modifier: Modifier = Modifier,
) {
    if (!motion.mounted || state.playlistId == null) return
    PlaylistToolbar(
        layer = backdropLayer,
        bounds = backdropBounds,
        onBack = state::back,
        showCollection = state.showCollection,
        saved = state.saved,
        updating = state.updating,
        onToggleSaved = state::toggleSaved,
        modifier = modifier,
    )
}
