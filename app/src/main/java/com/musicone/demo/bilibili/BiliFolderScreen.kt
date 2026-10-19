package com.musicone.demo

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
internal fun BiliFolderScreen(state: BiliLibraryUiState, model: BiliLibraryViewModel, bottomInset: Dp,
    onBackChange: ((() -> Boolean)?) -> Unit) {
    val account = state.account ?: return
    val selected = state.selectedFolder ?: return
    val directory by model.folderDirectory.state.collectAsState()
    val folder = directory.folders.firstOrNull { it.id == selected.id } ?: selected
    val search = rememberPlaylistSearchState("bili:${account.mid}:${folder.id}")
    val query = search.query.trim()
    val repository = model.folderRepository
    val entry = remember(repository, account.mid, folder.id, query) { repository.entry(account.mid, folder.id, query) }
    val content by entry.state.collectAsState()
    val scope = rememberCoroutineScope()
    val scroll = rememberLazyListState()
    LaunchedEffect(entry) {
        if (query.isNotEmpty()) { scroll.scrollToItem(0); delay(280) }
        biliFolderAttempt {
            val snapshot = repository.first(account, entry, refresh = true)
            if (query.isEmpty()) model.updateFolderCover(account, folder.id, snapshot.videos)
        }
    }
    ObserveBiliFolderPagination(scroll, entry) { repository.more(account, entry) }
    val videos = content.snapshot?.videos.orEmpty()
    val tracks = remember(videos) { videos.map(BiliTrackMapper::toFallbackTrack) }
    val playlist = remember(folder, tracks, query) { folder.asPlaylist(tracks).copy(id = "bili:${account.mid}:${folder.id}:$query") }
    BiliFolderContentScope {
    QqPlaylistSongMenuHost(playlist, emptyList(), bottomInset, {}, {}, LocalMusicFavorites.current.toggle, addOnly = true) {
        val selection = LocalPlaylistMultiSelection.current!!
        val menu = LocalQqPlaylistSongMenu.current!!
        SideEffect {
            selection.update(tracks)
            onBackChange {
                when {
                    menu.expanded -> { menu.back(); true }
                    selection.active -> { selection.close(); true }
                    search.expanded -> { search.close(); true }
                    else -> false
                }
            }
        }
        DisposableEffect(entry) { onDispose { onBackChange(null) } }
        BackHandler(state.visiblePage() == BiliLibraryPage.FOLDER && search.expanded && !selection.active && !menu.expanded, search::close)
        val results = rememberPlaylistSearchResults(tracks, animated = true)
        val backdrop = rememberGraphicsLayer()
        var bounds by remember { mutableStateOf(Rect.Zero) }
        BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInRoot() }) {
            val gutter = maxOf(if (maxWidth >= 600.dp) 32.dp else 20.dp, (maxWidth - 1120.dp) / 2)
            LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .playerQualityBackdropSnapshot(backdrop, true, captureContinuously = true), state = scroll,
                contentPadding = PaddingValues(start = gutter, end = gutter, bottom = bottomInset + 96.dp)) {
                item("header") { BiliFolderHeader(folder, state, model) }
                item("selection") { PlaylistSelectionToolbar {
                    repository.complete(account, entry).videos.map(BiliTrackMapper::toFallbackTrack)
                } }
                if (content.snapshot == null && content.error == null) {
                    items(6, key = { "placeholder-$it" }) { PlaylistTrackPlaceholder(Modifier) }
                }
                items(results.rows, key = { it.track.id }, contentType = { "playlist-track" }) { row ->
                    PlaylistSearchRowVisibility(row, results) {
                        BiliFavoriteVideoRow(row.track, model, interactive = row.track.id in results.targetIds)
                    }
                }
                if (videos.isEmpty() && content.snapshot != null && content.error == null) item("empty") {
                    QqInlineMessage(if (query.isBlank()) "收藏夹中暂无视频" else "没有找到相关视频")
                }
                content.error?.let { error -> item("retry") {
                    Column {
                        QqInlineMessage(error)
                        TextButton(onClick = { scope.launch { biliFolderAttempt {
                            if (content.retryFirst) repository.first(account, entry, refresh = true) else repository.more(account, entry)
                        } } }, enabled = !content.loading) { Text("重试") }
                    }
                } }
                state.error?.let { error -> item("action-error") { QqInlineMessage(error) } }
                state.message?.let { message -> item("action-message") { QqInlineMessage(message) } }
            }
            PlaylistFloatingTools(search, false, {}, backdrop, bounds, bottomInset, { 1f },
                Modifier.align(Alignment.BottomEnd), showLocate = false)
        }
    }
    }
}

/** 设置中的收藏夹不继承根歌单的关闭进度，否则复用歌曲行会一直透明。 */
@Composable
internal fun BiliFolderContentScope(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPlaylistMotion provides null, LocalPlaylistContentEntrance provides { 1f }, content = content)
}

/** 与普通歌单相同，在末尾八项内预取；错误时等待明确重试，避免无休止循环。 */
@Composable
private fun ObserveBiliFolderPagination(scroll: LazyListState, entry: BiliFolderEntry, more: suspend () -> Unit) {
    val latestMore by rememberUpdatedState(more)
    val content = rememberUpdatedState(entry.state.collectAsState().value)
    LaunchedEffect(scroll, entry) {
        snapshotFlow {
            val state = content.value
            val layout = scroll.layoutInfo
            state.snapshot?.page?.takeIf {
                state.snapshot.hasMore && !state.loading && state.error == null && layout.visibleItemsInfo.isNotEmpty() &&
                    (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 9
            }
        }.distinctUntilChanged().collect { if (it != null) biliFolderAttempt { latestMore() } }
    }
}

private suspend fun biliFolderAttempt(action: suspend () -> Unit) {
    try { action() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { /* 数据层保留错误、已有内容与重试游标。 */ }
}
