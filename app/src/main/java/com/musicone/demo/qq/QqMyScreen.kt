package com.musicone.demo

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

@Composable
internal fun QqMyScreen(
    account: MusicAccount?, library: QqLibraryUiState, bottomInset: Dp,
    onOpenSettings: () -> Unit, onOpenPlaylist: (MusicPlaylist) -> Unit, onRefresh: () -> Unit,
    onPlayPlaylist: (MusicPlaylist) -> Unit,
) {
    val recentPlayViewModel: QqRecentPlayViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val recentPlay by recentPlayViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val musicLibraryUi = LocalMusicLibraryUi.current
    val editor = remember { QqPlaylistEditorState(PlatformPreferences(context), scope, musicLibraryUi.store, context.applicationContext) }
    var host by remember { mutableStateOf(Rect.Zero) }
    var hiddenId by remember { mutableStateOf<String?>(null) }
    val removals = remember(account?.userId) { PlaylistCardRemovalState() }
    val editorBackdrop = rememberGraphicsLayer()
    var section by rememberSaveable { mutableIntStateOf(0) }
    val created = section == 0
    val localSection = section == 2
    val scroll = rememberLazyGridState()
    val gridTopPadding = 88.dp
    val headerInset = with(LocalDensity.current) { gridTopPadding.toPx() }
    val (segmentMotion, switchSegment) = rememberLibrarySegmentSwitch(
        section, scroll, headerInset,
    ) { section = it }
    val playlists = if (localSection) musicLibraryUi.snapshot.localPlaylists else if (created) library.createdPlaylists else library.collectedPlaylists
    val loading = if (created) library.loadingCreated else library.loadingCollected
    val message = if (created) library.createdMessage else library.collectedMessage
    val entry = LocalAccountEntry.current ?: onOpenSettings
    val favorite = library.favoritePlaylist ?: MusicPlaylist(QQ_FAVORITES_PLAYLIST_ID, MusicSource.QQ,
        "我喜欢", "", "", 0, 0xFFE6B8C8, 0xFF98677D, "喜", emptyList())
    val availablePlaylists = removals.present(playlists.filterNot { it.id == favorite.id })
    val reorder = rememberPlaylistCardReorder(scroll, availablePlaylists,
        if (localSection) "local" else "qq:${account?.userId.orEmpty()}:$section", localSection)
    val displayedPlaylists = reorder.arrange(availablePlaylists)
    val myVisible = LocalMyPageVisible.current
    ObserveQqRecentPlayVisibility(recentPlayViewModel, myVisible && LocalRecommendationVisible.current)
    LaunchedEffect(library.signedIn, myVisible) {
        if (library.signedIn && myVisible) recentPlayViewModel.ensureLoaded(force = true)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { recentPlayViewModel.ensureLoaded(force = true) }
    ReportPrimaryHeaderScroll(MusicOnePage.MY, scroll)
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { host = it.boundsInRoot() }, contentAlignment = Alignment.TopCenter) {
        val columns = qqLibraryColumns(maxWidth)
        val gutter = maxOf(if (maxWidth >= 600.dp) 32.dp else 20.dp, (maxWidth - 1120.dp) / 2)
        val favoriteWidth = if (maxWidth >= 600.dp)
            (maxWidth - gutter * 2 - 16.dp * (columns - 1)) / columns else maxWidth - gutter * 2
        val pairedWidth = if (maxWidth >= 600.dp) favoriteWidth * 1.08f else null
        val cardSide = (maxWidth - gutter * 2 - 16.dp * (columns - 1)) / columns
        val trailingSpace = key(section) { playlistGridTrailingSpace(displayedPlaylists.size, columns, cardSide) }
        LazyVerticalGrid(GridCells.Fixed(columns), state = scroll, modifier = Modifier.fillMaxSize()
            .onGloballyPositioned { segmentMotion.viewportTop = it.positionInRoot().y }
            .librarySegmentMotion(segmentMotion, section)
            .playerQualityBackdropSnapshot(editorBackdrop, editor.open),
            userScrollEnabled = !segmentMotion.moving && !reorder.dragging && !editor.open,
            contentPadding = PaddingValues(start = gutter, end = gutter, top = gridTopPadding, bottom = bottomInset + 28.dp + trailingSpace),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item("profile", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.animateContentSize(musicSpring()).padding(bottom = 12.dp)) { QqProfileHeader(account, onOpenSettings) }
            }
            item("favorites", span = { GridItemSpan(maxLineSpan) }) {
                val open = {
                    if (!library.signedIn) entry()
                    else library.favoritePlaylist?.let(onOpenPlaylist) ?: onRefresh()
                }
                val presented = favorite.copy(title = "我喜欢",
                    subtitle = if (library.signedIn) "${favorite.count} 首歌曲" else "登录后同步")
                // 两张卡片占用固定槽位，横滑溢出由整页视口裁剪，不能在网格留白处提前截断。
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                    PairedLibraryCards(presented, musicLibraryUi.snapshot.unified,
                        onOpen = { if (it.id == favorite.id) open() else onOpenPlaylist(it) },
                        onPlay = { if (it.id == favorite.id) library.favoritePlaylist?.let(onPlayPlaylist) ?: open() else onPlayPlaylist(it) },
                        tabletCardWidth = pairedWidth)
                }
            }
            item("library-heading", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = 12.dp).onGloballyPositioned {
                    // 保留屏幕外的真实位置，不能把父级裁剪后的可见边界用于滚动补位。
                    val top = it.positionInRoot().y
                    segmentMotion.headerTop = top
                    segmentMotion.contentTop = top + it.size.height
                }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("我的歌单", fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { if (localSection) editor.show(null, Rect.Zero, local = true) else if (library.signedIn) editor.show(null, Rect.Zero) else entry() }) {
                            Icon(Icons.Rounded.Add, "新建歌单")
                        }
                    }
                    LibrarySectionTabs(section, listOf("创建 ${library.createdPlaylists.size}", "收藏 ${library.collectedPlaylists.size}", "本地 ${musicLibraryUi.snapshot.localPlaylists.size}"), switchSegment)
                }
            }
            items(displayedPlaylists, key = { it.id }) { playlist ->
                val presented = playlist.withQqLibrarySongCountSubtitle()
                val artworkVersion = library.artworkVersions[playlist.id] ?: 0L
                QqPlaylistCard(
                    playlist = presented,
                    onClick = { onOpenPlaylist(presented) },
                    onPlay = { onPlayPlaylist(presented) },
                    modifier = Modifier.fillMaxWidth().playlistCardLift(reorder, playlist.id).graphicsLayer {
                        alpha = if (hiddenId == playlist.id || removals.hidden(playlist.id)) 0f else 1f
                    }.animateItem(
                        fadeInSpec = if (segmentMotion.moving) null else musicMotion(320),
                        fadeOutSpec = if (segmentMotion.moving || removals.hidden(playlist.id)) null else musicMotion(220),
                        placementSpec = if (segmentMotion.moving || reorder.activeId == playlist.id) null else musicSpring(),
                    ),
                    width = 1280.dp,
                    artworkVersion = artworkVersion,
                    sourceKey = if (localSection) "library-user:${playlist.id}" else "qq-my-library:$created:${playlist.id}",
                    freezeArtwork = hiddenId == playlist.id,
                    reorderState = reorder,
                    onLongClick = if (created || localSection) { origin ->
                        removals.hold(playlist, displayedPlaylists.indexOfFirst { it.id == playlist.id })
                        hiddenId = playlist.id
                        editor.show(presented, origin.bounds, artworkVersion, origin.artwork, local = localSection,
                            currentBounds = origin.currentBounds)
                    } else null,
                )
            }
            if (displayedPlaylists.isEmpty()) item("status", span = { GridItemSpan(maxLineSpan) }) {
                QqInlineMessage(when {
                    localSection -> "还没有本地歌单，点击右上角创建"
                    !library.signedIn -> "登录后，你的音乐收藏会出现在这里"
                    loading -> "正在同步歌单…"
                    else -> message ?: if (created) "还没有创建的歌单" else "还没有收藏的歌单"
                })
            }
            item("recent-play", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = musicSpring())
                    .animateContentSize(musicSpring())) {
                    QqRecentPlaySection(recentPlay, library.signedIn, onOpenPlaylist)
                }
            }
        }
        if (segmentMotion.moving) Box(Modifier.matchParentSize().clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
        ) {})
        QqPlaylistEditorOverlay(
            editor = editor,
            host = host,
            backdrop = editorBackdrop,
            bottomInset = bottomInset,
            onChanged = { action, target ->
                if (action == PlaylistEditMode.Delete && target != null) removals.confirmDelete(target.id)
                else if (!editor.local) onRefresh()
            },
            onRestored = { hiddenId = null },
            onClosed = {
                if (removals.finish() != null && !editor.local) onRefresh()
            },
        )
    }
}

internal fun qqLibraryColumns(width: Dp): Int = when { width >= 1000.dp -> 4; width >= 600.dp -> 3; else -> 2 }

internal fun MusicPlaylist.withQqLibrarySongCountSubtitle(): MusicPlaylist =
    copy(subtitle = "$count 首歌曲")
