package com.musicone.source.webdav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicone.source.sdk.SourceComponentScope
import com.musicone.source.sdk.SourcePlayerState
import com.musicone.source.sdk.SourceTrackListState
import com.musicone.source.webdav.internal.WebDavPaths
import kotlinx.coroutines.launch

/** 手机和平板右栏共用：标题、播放操作、文件夹、歌曲在同一个列表里滚动。 */
@Composable
internal fun WebDavDirectoryPage(
    ui: SourceComponentScope,
    controller: WebDavBrowserController,
    view: WebDavDirectoryView,
    trackState: SourceTrackListState,
    player: SourcePlayerState,
    serverName: String,
    gutter: Dp,
    bottomInset: Dp,
    importOpen: Boolean,
    onImportOpenChange: (Boolean) -> Unit,
) {
    val motion = LocalWebDavMotion.current
    val scope = rememberCoroutineScope()
    val title = WebDavPaths.fileNameOf(view.path).ifBlank { serverName }
    val query = trackState.query.trim()
    val ordered = remember(view.tracks, controller.sort) { sortedWebDavTracks(view.tracks, controller.sort) }
    val tracks = remember(ordered, query) { ordered.matchingWebDavQuery(query) }
    val directories = remember(view.directories, query) {
        if (query.isEmpty()) view.directories else view.directories.filter { it.name.contains(query, ignoreCase = true) }
    }
    val ids = remember(view.tracks.map { it.stableId }) { view.tracks.associate { webDavHostTrackId(it) to it.stableId } }
    val playingId = playingWebDavTrackId(player, ids)
    val skeleton = view.loading && !view.loaded
    val scroll = rememberDirectoryScroll(controller, view)
    LaunchedEffect(query) { if (query.isNotEmpty()) scroll.scrollToItem(0) }

    // 与下方列表项顺序一致，供定位当前歌曲使用。
    val firstTrackIndex = 3 + (if (view.error != null) 1 else 0) +
        (if (directories.isNotEmpty()) directories.size + 1 else 0) + 1
    val playingIndex = if (playingId == null) -1 else tracks.indexOfFirst { it.stableId == playingId }

    Column(Modifier.fillMaxSize()) {
        // 宿主工具栏自带展开和收起动画，需常驻组合。
        Box(Modifier.padding(horizontal = (gutter - 12.dp).coerceAtLeast(0.dp))) { ui.SelectionToolbar(trackState) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = scroll,
                contentPadding = PaddingValues(start = gutter, end = gutter, bottom = bottomInset + 96.dp),
            ) {
                item("title") {
                    WebDavFolderTitle(ui, view, title, controller.bookmarks.any { it.path == view.path },
                        controller::toggleBookmark, controller::refresh)
                }
                item("actions") {
                    WebDavPlayActions(
                        enabled = controller.busyMessage == null && (view.tracks.isNotEmpty() || view.directories.isNotEmpty()),
                        importOpen = importOpen,
                        showIncludeToggle = view.directories.isNotEmpty() && view.tracks.isNotEmpty(),
                        includeSubfolders = controller.includeSubfolders,
                        onIncludeChange = { controller.includeSubfolders = it },
                        onPlay = { controller.playFolder(ordered, shuffle = false) },
                        onShuffle = { controller.playFolder(ordered, shuffle = true) },
                        onImport = { onImportOpenChange(!importOpen) },
                    )
                }
                item("import") {
                    ExpandingSection(importOpen) {
                        WebDavImportPanel(ui, controller, title) { destination, newTitle ->
                            onImportOpenChange(false)
                            controller.importFolder(ordered, destination, newTitle)
                        }
                    }
                }
                view.error?.let { error ->
                    item("error") {
                        Column(
                            Modifier.animateItem(motion.spec(300), motion.spec(260), motion.spec(180)).padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ui.InlineMessage(error)
                            ui.ActionButton("重试", enabled = !view.loading, primary = false, onClick = controller::refresh)
                        }
                    }
                }
                if (directories.isNotEmpty()) {
                    item("folders-title") {
                        WebDavSectionTitle("文件夹", Modifier.animateItem(motion.spec(300), motion.spec(260), motion.spec(180)))
                    }
                    items(directories, key = { "dir:${it.path}" }, contentType = { "folder" }) { dir ->
                        WebDavFolderRow(
                            title = dir.name,
                            subtitle = controller.summaries[dir.path]?.let { webDavCountText(it.subdirCount, it.audioCount) },
                            modifier = Modifier.animateItem(motion.spec(300), motion.spec(260), motion.spec(180)),
                        ) { controller.open(dir.path) }
                    }
                }
                if (tracks.isNotEmpty()) {
                    item("tracks-title") {
                        WebDavSectionTitle("歌曲", Modifier.animateItem(motion.spec(300), motion.spec(260), motion.spec(180))) {
                            ui.SortButton(WebDavTrackSort.options, controller.sort.name) { controller.sort = WebDavTrackSort.of(it) }
                        }
                    }
                    items(tracks, key = { "trk:${it.stableId}" }, contentType = { "track" }) { track ->
                        val current = track.stableId == playingId
                        ui.SelectionRow(
                            track = track,
                            state = trackState,
                            current = current,
                            playing = current && player.isPlaying,
                            modifier = Modifier.animateItem(motion.spec(300), motion.spec(320), motion.spec(180)),
                            onClick = { controller.playTrack(ordered, track.stableId) },
                            onFavorite = {},
                        )
                    }
                    item("summary") {
                        Text(webDavSummary(tracks.size, tracks.sumOf { it.durationMs }), Modifier.padding(vertical = 20.dp),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (skeleton) {
                    items(8, key = { "skeleton-$it" }, contentType = { "skeleton" }) {
                        WebDavRowPlaceholder(Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = motion.spec(180)))
                    }
                } else if (view.loaded && directories.isEmpty() && tracks.isEmpty()) {
                    item("empty") {
                        val searching = query.isNotEmpty()
                        WebDavEmpty(
                            icon = if (searching) Icons.Rounded.SearchOff else Icons.Rounded.FolderOff,
                            title = if (searching) "没有找到相关内容" else "这里没有歌曲",
                            message = if (searching) null else "此文件夹下没有子文件夹，也没有支持的音频文件",
                            modifier = Modifier.animateItem(motion.spec(300), motion.spec(260), motion.spec(180)),
                        )
                    }
                }
            }
            WebDavFloatingTools(
                visible = view.tracks.isNotEmpty() || view.directories.isNotEmpty(),
                bottomInset = bottomInset,
                modifier = Modifier.align(Alignment.BottomEnd),
            ) {
                ui.SearchTools(trackState, canLocate = playingIndex >= 0, onLocate = {
                    scope.launch { scroll.locate(firstTrackIndex + playingIndex, motion.reduce) }
                })
            }
        }
    }
}

@Composable
private fun ExpandingSection(visible: Boolean, content: @Composable () -> Unit) {
    val motion = LocalWebDavMotion.current
    AnimatedVisibility(
        visible,
        enter = expandVertically(motion.spec(320)) + fadeIn(motion.spec(260)),
        exit = shrinkVertically(motion.spec(260)) + fadeOut(motion.spec(180)),
    ) { content() }
}

@Composable
internal fun WebDavSectionTitle(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        action?.invoke()
    }
}

/** 搜索按钮随底栏和键盘平滑抬升，规则与宿主歌单页一致。 */
@Composable
private fun WebDavFloatingTools(visible: Boolean, bottomInset: Dp, modifier: Modifier, content: @Composable () -> Unit) {
    val motion = LocalWebDavMotion.current
    val ime = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val bottom by animateDpAsState(maxOf(bottomInset, ime) + 20.dp, motion.spec(220), label = "搜索工具位置")
    AnimatedVisibility(
        visible,
        modifier.padding(end = 20.dp).offset { IntOffset(0, -bottom.roundToPx()) },
        enter = fadeIn(motion.spec(220)),
        exit = fadeOut(motion.spec(160)),
    ) { content() }
}

/** 每个目录各记自己的滚动位置；只保存数值，不跨页面持有列表状态对象。 */
@Composable
private fun rememberDirectoryScroll(controller: WebDavBrowserController, view: WebDavDirectoryView): LazyListState {
    val serverKey = view.serverKey
    val path = view.path
    val state = remember(serverKey, path) {
        val saved = if (view.loaded) controller.scrollPosition(path) else WebDavScrollPosition()
        LazyListState(saved.index, saved.offset)
    }
    DisposableEffect(state) {
        onDispose {
            controller.saveScrollPosition(serverKey, path,
                WebDavScrollPosition(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset))
        }
    }
    return state
}

private suspend fun LazyListState.locate(index: Int, immediate: Boolean) {
    val lead = -(layoutInfo.viewportSize.height / 3)
    if (immediate) scrollToItem(index, lead) else animateScrollToItem(index, lead)
}

private fun webDavSummary(count: Int, durationMs: Long): String =
    if (durationMs <= 0L) "$count 首歌曲" else "$count 首歌曲 · 约 ${(durationMs / 60_000).coerceAtLeast(1)} 分钟"
