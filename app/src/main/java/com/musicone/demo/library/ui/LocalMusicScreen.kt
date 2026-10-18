package com.musicone.demo

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect

@Composable
internal fun LocalMusicScreen(state: MusicOneUiState, bottomInset: Dp, onOpen: (MusicPlaylist) -> Unit,
    onPlay: (MusicPlaylist) -> Unit, onManage: (Rect) -> Unit) {
    val ui = LocalMusicLibraryUi.current
    val importMenu = rememberLocalImportMenu()
    val scroll = rememberLazyGridState()
    ReportPrimaryHeaderScroll(state.page, scroll)
    LibraryPlaylistEditorHost(bottomInset) { editor ->
    val visiblePlaylists = editor.removals.present(ui.snapshot.localPlaylists)
    val reorder = rememberPlaylistCardReorder(scroll, visiblePlaylists, "local", local = true)
    val displayedPlaylists = reorder.arrange(visiblePlaylists)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = qqLibraryColumns(maxWidth)
        val gutter = maxOf(if (maxWidth >= 600.dp) 32.dp else 20.dp, (maxWidth - 1120.dp) / 2)
        val pairedWidth = if (maxWidth >= 600.dp) ((maxWidth - gutter * 2 - 16.dp * (columns - 1)) / columns) * 1.08f else null
        val cardSide = (maxWidth - gutter * 2 - 16.dp * (columns - 1)) / columns
        val trailingSpace = playlistGridTrailingSpace(displayedPlaylists.size, columns, cardSide)
        LazyVerticalGrid(GridCells.Fixed(columns), state = scroll, contentPadding = PaddingValues(start = gutter, end = gutter, top = 88.dp, bottom = bottomInset + 28.dp + trailingSpace),
            userScrollEnabled = !reorder.dragging && !editor.state.open,
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item("imports", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.animateContentSize(musicSpring())) {
                    LocalLibraryToolbar(!ui.busy, importMenu, onManage)
                    if (ui.busy) LibraryActionRow(ui.progress.ifBlank { "正在处理…" }, "点击取消当前任务", onClick = ui::cancel)
                    ui.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            item("local-top", span = { GridItemSpan(maxLineSpan) }) {
                PairedLibraryCards(ui.snapshot.allLocal, ui.snapshot.unified, onOpen, onPlay, tabletCardWidth = pairedWidth)
            }
            item("heading", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("我的歌单", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { editor.state.show(null, Rect.Zero, local = true) }, enabled = !ui.busy) {
                        Icon(Icons.Default.Add, "新建本地歌单")
                    }
                }
            }
            items(displayedPlaylists, key = { it.id }) { playlist ->
                QqPlaylistCard(playlist, { onOpen(playlist) }, { onPlay(playlist) },
                    Modifier.fillMaxWidth().playlistCardLift(reorder, playlist.id).graphicsLayer {
                        alpha = if (editor.hiddenId == playlist.id || editor.removals.hidden(playlist.id)) 0f else 1f
                    }.animateItem(placementSpec = if (reorder.activeId == playlist.id) null else musicSpring()),
                    width = 1280.dp, sourceKey = "library-user:${playlist.id}",
                    freezeArtwork = editor.hiddenId == playlist.id, reorderState = reorder,
                    onLongClick = { editor.show(playlist, it, displayedPlaylists.indexOfFirst { shown -> shown.id == playlist.id }) })
            }
        }
    }
    }
}

@Composable
internal fun rememberLocalImportMenu(): () -> Unit {
    val ui = LocalMusicLibraryUi.current
    val menu = LocalPlaybackSettingsMenu.current
    return { menu?.open("导入本地音乐", Offset.Zero, listOf(
        PlaybackSettingsMenuOption("选择音乐文件", false) { ui.chooseFiles() },
        PlaybackSettingsMenuOption("关联音乐目录", false) { ui.chooseDirectory(null) },
        PlaybackSettingsMenuOption(if (ui.copyFiles) "导入方式：复制到应用目录" else "导入方式：引用原文件", ui.copyFiles) { ui.copyFiles = !ui.copyFiles },
    )) }
}
