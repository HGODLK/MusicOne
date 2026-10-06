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
import androidx.compose.ui.unit.Dp

/** 把搜索与定位按钮提升到根叠加层，避免浮动玻璃被录入自己的背景采样源。 */
@Stable
internal class PlaylistFloatingToolsOverlayState {
    var playlistId by mutableStateOf<String?>(null)
        private set
    var search by mutableStateOf<PlaylistSearchState?>(null)
        private set
    var canLocate by mutableStateOf(false)
        private set
    var visible by mutableStateOf(true)

    private var locateAction: () -> Unit = {}

    fun update(
        playlistId: String,
        search: PlaylistSearchState,
        canLocate: Boolean,
        onLocate: () -> Unit,
    ) {
        this.playlistId = playlistId
        this.search = search
        this.canLocate = canLocate
        locateAction = onLocate
    }

    fun clear(playlistId: String) {
        if (this.playlistId != playlistId) return
        this.playlistId = null
        search = null
        canLocate = false
        locateAction = {}
    }

    fun locate() = locateAction()
}

@Composable
internal fun rememberPlaylistFloatingToolsOverlayState(): PlaylistFloatingToolsOverlayState =
    remember { PlaylistFloatingToolsOverlayState() }

@Composable
internal fun PlaylistFloatingToolsOverlay(
    state: PlaylistFloatingToolsOverlayState,
    motion: PageMotion,
    backdropLayer: GraphicsLayer,
    backdropBounds: Rect,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val search = state.search ?: return
    if (!motion.mounted || state.playlistId == null) return
    PlaylistFloatingTools(
        state = search,
        canLocate = state.canLocate,
        onLocate = state::locate,
        backdropLayer = backdropLayer,
        backdropBounds = backdropBounds,
        bottomInset = bottomInset,
        motionProgress = { motion.value },
        modifier = modifier,
    )
}
