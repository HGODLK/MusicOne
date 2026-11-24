package com.musicone

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
    // 自动整理出来的专辑、歌手歌单单独分区，不参与手建歌单的拖动排序。
    val manualPlaylists = visiblePlaylists.filter { autoPlaylistKind(it.id) == null }
    val reorder = rememberPlaylistCardReorder(scroll, manualPlaylists, "local", local = true)
    val displayedPlaylists = reorder.arrange(manualPlaylists)
    // 卡片多选：手机存储是固定歌单，不能选。
    val selection = remember { PlaylistCardSelection() }
    val selectionHost = LocalPlaylistSelectionBarHost.current
    val selectableIds = visiblePlaylists.map { it.id }.filter { it != PHONE_STORAGE_PLAYLIST_ID }
    val autoGroups = AutoPlaylistKind.entries.map { kind -> kind to visiblePlaylists.filter { autoPlaylistKind(it.id) == kind } }
    LaunchedEffect(selectableIds) { selection.retain(selectableIds) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = mainSourceLibraryColumns(maxWidth)
        val gutter = maxOf(if (maxWidth >= 600.dp) 32.dp else 20.dp, (maxWidth - 1120.dp) / 2)
        val pairedWidth = if (maxWidth >= 600.dp) ((maxWidth - gutter * 2 - 16.dp * (columns - 1)) / columns) * 1.08f else null
        val cardSide = (maxWidth - gutter * 2 - 16.dp * (columns - 1)) / columns
        val trailingSpace = playlistGridTrailingSpace(displayedPlaylists.size, columns, cardSide)
        SideEffect {
            selection.slots = playlistGridSlots(listOf(displayedPlaylists.map { it.id }) +
                autoGroups.map { (_, group) -> group.map { it.id } }, columns)
        }
        LazyVerticalGrid(GridCells.Fixed(columns), state = scroll, contentPadding = PaddingValues(start = gutter, end = gutter, top = 88.dp, bottom = bottomInset + 28.dp + trailingSpace),
            userScrollEnabled = !reorder.dragging && !editor.state.open,
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item("imports", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.animateContentSize(musicSpring())) {
                    LocalLibraryToolbar(!ui.busy, importMenu, onManage)
                    if (ui.busy) LibraryActionRow(ui.progress.ifBlank { "正在处理…" }, "点击取消当前任务", onClick = ui::cancel)
                    LibraryTransientMessage(ui)
                }
            }
            item("local-top", span = { GridItemSpan(maxLineSpan) }) {
                PairedLibraryCards(ui.snapshot.allLocal, ui.snapshot.unified, onOpen, onPlay, tabletCardWidth = pairedWidth)
            }
            item("heading", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("我的歌单", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    // 平时是多选图标和新建按钮，多选时整组原位淡出，“全选”在同一槽位淡入。
                    PlaylistSectionSelectAll(selection, displayedPlaylists.map { it.id }.filter { it != PHONE_STORAGE_PLAYLIST_ID },
                        { selection.begin(null) }.takeIf { !ui.busy && selectableIds.isNotEmpty() }) {
                        IconButton(onClick = { editor.state.show(null, Rect.Zero, local = true) }, enabled = !ui.busy) {
                            Icon(Icons.Default.Add, "新建本地歌单")
                        }
                    }
                }
            }
            items(displayedPlaylists, key = { it.id }) { playlist ->
                SelectablePlaylistCard(selection, playlist.id, playlist.id != PHONE_STORAGE_PLAYLIST_ID,
                    Modifier.fillMaxWidth().playlistCardLift(reorder, playlist.id).graphicsLayer {
                        alpha = if (editor.hiddenId == playlist.id || editor.removals.hidden(playlist.id)) 0f else 1f
                    }.animateItem(placementSpec = if (reorder.activeId == playlist.id) null else musicSpring())) { hintVisibility ->
                    MainSourcePlaylistCard(playlist, { onOpen(playlist) }, { onPlay(playlist) }, Modifier.fillMaxWidth(),
                        width = 1280.dp, sourceKey = "library-user:${playlist.id}",
                        freezeArtwork = editor.hiddenId == playlist.id,
                        // 多选期间停用拖动排序；播放按钮淡出但保留位置，点击由多选层接管。
                        reorderState = reorder.takeUnless { selection.active }, playVisibility = hintVisibility,
                        onLongClick = { editor.show(playlist, it, visiblePlaylists.indexOfFirst { shown -> shown.id == playlist.id }) })
                }
            }
            autoGroups.forEach { (kind, group) ->
                if (group.isNotEmpty()) {
                    item("auto-heading-${kind.name}", span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(kind.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            PlaylistSectionSelectAll(selection, group.map { it.id })
                        }
                    }
                    items(group, key = { it.id }) { playlist ->
                        SelectablePlaylistCard(selection, playlist.id, true,
                            Modifier.fillMaxWidth().graphicsLayer {
                                alpha = if (editor.hiddenId == playlist.id || editor.removals.hidden(playlist.id)) 0f else 1f
                            }.animateItem(placementSpec = musicSpring())) { hintVisibility ->
                            MainSourcePlaylistCard(playlist, { onOpen(playlist) }, { onPlay(playlist) }, Modifier.fillMaxWidth(),
                                width = 1280.dp, sourceKey = "library-user:${playlist.id}",
                                freezeArtwork = editor.hiddenId == playlist.id, playVisibility = hintVisibility,
                                onLongClick = { editor.show(playlist, it, visiblePlaylists.indexOfFirst { shown -> shown.id == playlist.id }) })
                        }
                    }
                }
            }
        }
        PlaylistCardSelectionBar(selection, selectableIds, "删除") {
            val targets = visiblePlaylists.filter { it.id in selection.ids }
            selectionHost.confirm = PlaylistBatchConfirm("删除 ${targets.size} 个歌单？",
                playlistBatchNames(targets) + "\n只删除歌单，原音乐文件会保留。", "删除") {
                ui.work("正在删除歌单…") {
                    // 先退出多选、被删的卡片淡出缩小，再删除让其余卡片补位。
                    val ids = targets.map { it.id }
                    selection.close()
                    selection.markLeaving(ids)
                    kotlinx.coroutines.delay(PlaylistCardLeaveMillis)
                    val removed = try { ui.store.deletePlaylists(ids) }
                        catch (failure: Exception) { selection.clearLeaving(); throw failure }
                    ui.message = "已删除 $removed 个歌单"
                }
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
