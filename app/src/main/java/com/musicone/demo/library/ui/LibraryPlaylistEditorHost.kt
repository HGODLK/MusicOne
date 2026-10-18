package com.musicone.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp

@Stable
internal class LibraryPlaylistCardEditor(val state: QqPlaylistEditorState, val backdrop: GraphicsLayer) {
    val removals = PlaylistCardRemovalState()
    var hiddenId by mutableStateOf<String?>(null)
        private set
    fun show(playlist: MusicPlaylist, origin: QqPlaylistCardOrigin, index: Int) {
        if (state.busy || state.open) return
        removals.hold(playlist, index)
        hiddenId = playlist.id
        state.show(playlist, origin.bounds, artwork = origin.artwork, local = true, currentBounds = origin.currentBounds)
    }
    fun restore() { hiddenId = null }
}

/** 本地歌单直接挂载 QQ 同一个卡片编辑层，菜单、输入和返回保持连续。 */
@Composable
internal fun LibraryPlaylistEditorHost(bottomInset: Dp, content: @Composable (LibraryPlaylistCardEditor) -> Unit) {
    val ui = LocalMusicLibraryUi.current
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val backdrop = rememberGraphicsLayer()
    val state = remember { QqPlaylistEditorState(PlatformPreferences(context), scope, ui.store, context) }
    val editor = remember { LibraryPlaylistCardEditor(state, backdrop) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInRoot() }) {
        Box(Modifier.fillMaxSize().playerQualityBackdropSnapshot(backdrop, state.open)) { content(editor) }
        QqPlaylistEditorOverlay(state, bounds, backdrop, bottomInset, onChanged = { action, target ->
            if (action == PlaylistEditMode.Delete && target != null) editor.removals.confirmDelete(target.id)
        }, onRestored = editor::restore, onClosed = { editor.removals.finish() })
    }
}
