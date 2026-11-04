package com.musicone

import androidx.compose.animation.*
import androidx.compose.foundation.background
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
import com.musicone.source.sdk.SourceContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
internal fun SupplementalFolderScreen(state: SupplementalState, model: SupplementalSourceModel, bottomInset: Dp,
    onBackChange: ((() -> Boolean)?) -> Unit) {
    val selected = state.selectedCollection ?: return
    val folder = state.collections.firstOrNull { it.identity == selected.identity } ?: selected
    val search = rememberPlaylistSearchState("source:${model.sourceId}:${state.account?.id}:${folder.identity.remoteId}")
    val query = search.query.trim()
    val repository = model.repository ?: return
    val entry = remember(repository, state.account?.id, folder.identity, query) { repository.entry(folder, query) }
    val content by entry.state.collectAsState()
    val scope = rememberCoroutineScope(); val scroll = rememberLazyListState()
    LaunchedEffect(entry) {
        if (query.isNotEmpty()) { scroll.scrollToItem(0); delay(280) }
        supplementalAttempt { repository.first(entry, refresh = true) }
    }
    ObserveSupplementalPagination(scroll, entry) { repository.more(entry) }
    val contents = content.snapshot?.contents.orEmpty()
    val tracks = remember(contents) { contents.map { it.asSupplementalTrack() } }
    val contentByTrack = remember(contents, tracks) { tracks.zip(contents).associate { (track, item) -> track.id to item } }
    val selection = remember(entry) { PlaylistMultiSelection() }
    val menu = LocalPlaybackSettingsMenu.current
    val player by sourcePlayerStateForTemplate()
    CompositionLocalProvider(LocalPlaylistMotion provides null, LocalPlaylistContentEntrance provides { 1f },
        LocalPlaylistMultiSelection provides selection, LocalMainSourcePlaylistSongMenu provides null) {
        SideEffect {
            selection.update(tracks)
            onBackChange {
                when {
                    menu?.expanded == true -> { menu.dismiss(); true }
                    selection.active -> { selection.close(); true }
                    search.expanded -> { search.close(); true }
                    else -> false
                }
            }
        }
        DisposableEffect(entry) { onDispose { onBackChange(null) } }
        val results = rememberPlaylistSearchResults(tracks, animated = true)
        val backdrop = rememberGraphicsLayer(); var bounds by remember { mutableStateOf(Rect.Zero) }
        BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInRoot() }) {
            val gutter = maxOf(if (maxWidth >= 600.dp) 32.dp else 20.dp, (maxWidth - 1120.dp) / 2)
            LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .playerQualityBackdropSnapshot(backdrop, true, captureContinuously = true), state = scroll,
                contentPadding = PaddingValues(start = gutter, end = gutter, bottom = bottomInset + 96.dp)) {
                item("header") { SupplementalFolderHeader(folder, state, model) }
                item("selection") {
                    SupplementalSelectionToolbar(selection, state, model,
                        loadAll = { repository.complete(entry).contents.map { it.asSupplementalTrack() } },
                        selected = {
                            val complete = repository.complete(entry).contents
                            complete.filter { it.asSupplementalTrack().id in selection.ids }
                        })
                }
                if (content.snapshot == null && content.error == null) items(6, key = { "placeholder-$it" }) { PlaylistTrackPlaceholder(Modifier) }
                items(results.rows, key = { it.track.id }, contentType = { "playlist-track" }) { row ->
                    val item = contentByTrack[row.track.id] ?: return@items
                    PlaylistSearchRowVisibility(row, results) {
                        PlaylistTrackRow(row.track, current = player.stableTrackId == row.track.id, playing = player.isPlaying,
                            favorite = false, pendingRemoval = false, onClick = {
                                if (model.canPlay && model.canConvert(item)) model.playContent(item)
                                else if (model.pageAvailable(com.musicone.source.sdk.SourceSupplementalPage.CONTENT)) model.openContent(item)
                            }, onFavorite = {},
                            modifier = Modifier, enabled = row.track.id in results.targetIds,
                            onMore = if (!model.canPlay && !model.canImport && !model.pageAvailable(com.musicone.source.sdk.SourceSupplementalPage.CONTENT)) null else { anchor ->
                                menu?.open(row.track.title, anchor.center, buildList {
                                    if (model.canPlay && model.canConvert(item)) add(PlaybackSettingsMenuOption("立即播放", false) { model.playContent(item) })
                                    if (model.pageAvailable(com.musicone.source.sdk.SourceSupplementalPage.CONTENT)) add(PlaybackSettingsMenuOption(
                                        if (model.canImport) "选择子项目 / 加入歌单" else "查看内容", false) { model.openContent(item) })
                                    if (model.canImport || model.canPlay) add(PlaybackSettingsMenuOption("多选", false) { selection.begin(selection.key(row.track)) })
                                })
                            })
                    }
                }
                if (contents.isEmpty() && content.snapshot != null && content.error == null) item("empty") {
                    MainSourceInlineMessage(if (query.isBlank()) "收藏夹中暂无内容" else "没有找到相关内容")
                }
                content.error?.let { error -> item("retry") {
                    Column {
                        MainSourceInlineMessage(error)
                        TextButton(onClick = { scope.launch { supplementalAttempt {
                            if (content.retryFirst) repository.first(entry, refresh = true) else repository.more(entry)
                        } } }, enabled = !content.loading) { Text("重试") }
                    }
                } }
                state.error?.let { error -> item("action-error") { MainSourceInlineMessage(error) } }
                state.message?.let { message -> item("action-message") { MainSourceInlineMessage(message) } }
            }
            PlaylistFloatingTools(search, false, {}, backdrop, bounds, bottomInset, { 1f }, Modifier.align(Alignment.BottomEnd), showLocate = false)
        }
    }
}

@Composable
private fun SupplementalSelectionToolbar(selection: PlaylistMultiSelection, state: SupplementalState,
    model: SupplementalSourceModel, loadAll: suspend () -> List<MusicTrack>, selected: suspend () -> List<SourceContent>) {
    val scope = rememberCoroutineScope()
    AnimatedVisibility(selection.active, enter = expandVertically(musicMotion(220)) + fadeIn(musicMotion(180)),
        exit = shrinkVertically(musicMotion(180)) + fadeOut(musicMotion(140))) {
        Column {
            FlowRow(Modifier.fillMaxWidth().heightIn(min = 52.dp), itemVerticalAlignment = Alignment.CenterVertically, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("已选 ${selection.ids.size}", Modifier.padding(end = 8.dp))
                TextButton(onClick = { scope.launch { selection.selectAll(loadAll) } }, enabled = !state.busy && !selection.loading) { Text(if (selection.loading) "正在全选…" else "全选") }
                if (model.canImport) TextButton(onClick = { scope.launch { supplementalAttempt { model.importContents(selected()) } } }, enabled = selection.ids.isNotEmpty() && !state.busy) { Text("添加到") }
                if (model.canPlay) TextButton(onClick = { scope.launch { supplementalAttempt { model.importContents(selected(), play = true) } } }, enabled = selection.ids.isNotEmpty() && !state.busy) { Text("播放所选") }
                TextButton(onClick = selection::close, enabled = !state.busy) { Text("取消") }
            }
            if (model.canImport) SupplementalImportTarget(state, model)
            selection.error?.let { MainSourceInlineMessage(it) }
        }
    }
}

@Composable
private fun ObserveSupplementalPagination(scroll: LazyListState, entry: SupplementalContentEntry, more: suspend () -> Unit) {
    val latestMore by rememberUpdatedState(more)
    val content = rememberUpdatedState(entry.state.collectAsState().value)
    LaunchedEffect(scroll, entry) {
        snapshotFlow {
            val state = content.value; val snapshot = state.snapshot; val layout = scroll.layoutInfo
            snapshot?.page?.takeIf { snapshot.hasMore && !state.loading && state.error == null && layout.visibleItemsInfo.isNotEmpty() &&
                (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 9 }
        }.distinctUntilChanged().collect { if (it != null) supplementalAttempt { latestMore() } }
    }
}

private suspend fun supplementalAttempt(action: suspend () -> Unit) {
    try { action() } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { /* 数据层保留已有内容、错误与重试游标。 */ }
}

@Composable
private fun sourcePlayerStateForTemplate(): State<com.musicone.source.sdk.SourcePlayerState> =
    com.musicone.sourceintegration.SourceApplicationHost.get(androidx.compose.ui.platform.LocalContext.current).playerState.collectAsState()
