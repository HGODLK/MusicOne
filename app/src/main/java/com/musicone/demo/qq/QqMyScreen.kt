package com.musicone.demo

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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val editor = remember { QqPlaylistEditorState(PlatformPreferences(context), scope) }
    val haptic = LocalHapticFeedback.current
    var host by remember { mutableStateOf(Rect.Zero) }
    var hiddenId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteId by remember(account?.userId) { mutableStateOf<String?>(null) }
    var departingId by remember(account?.userId) { mutableStateOf<String?>(null) }
    var locallyDeletedIds by remember(account?.userId) { mutableStateOf(emptySet<String>()) }
    val editorBackdrop = rememberGraphicsLayer()
    var created by rememberSaveable { mutableStateOf(true) }
    val scroll = rememberLazyGridState()
    val gridTopPadding = 88.dp
    val headerInset = with(LocalDensity.current) { gridTopPadding.toPx() }
    val (segmentMotion, switchSegment) = rememberLibrarySegmentSwitch(
        created, scroll, headerInset,
    ) { created = it }
    val playlists = if (created) library.createdPlaylists else library.collectedPlaylists
    val loading = if (created) library.loadingCreated else library.loadingCollected
    val message = if (created) library.createdMessage else library.collectedMessage
    val entry = LocalAccountEntry.current ?: onOpenSettings
    val favorite = library.favoritePlaylist ?: MusicPlaylist(QQ_FAVORITES_PLAYLIST_ID, MusicSource.QQ,
        "我喜欢", "", "", 0, 0xFFE6B8C8, 0xFF98677D, "喜", emptyList())
    val displayedPlaylists = playlists.filterNot { it.id == favorite.id || it.id in locallyDeletedIds }
    val myVisible = LocalMyPageVisible.current
    LaunchedEffect(library.signedIn, myVisible) {
        if (library.signedIn && myVisible) recentPlayViewModel.ensureLoaded(force = true)
    }
    LaunchedEffect(library.createdPlaylists.map(MusicPlaylist::id)) {
        locallyDeletedIds = locallyDeletedIds.intersect(library.createdPlaylists.mapTo(mutableSetOf(), MusicPlaylist::id))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { recentPlayViewModel.ensureLoaded(force = true) }
    ReportPrimaryHeaderScroll(MusicOnePage.MY, scroll)
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { host = it.boundsInRoot() }, contentAlignment = Alignment.TopCenter) {
        val columns = qqLibraryColumns(maxWidth)
        val gutter = maxOf(if (maxWidth >= 600.dp) 32.dp else 20.dp, (maxWidth - 1120.dp) / 2)
        val favoriteWidth = if (maxWidth >= 600.dp)
            (maxWidth - gutter * 2 - 16.dp * (columns - 1)) / columns else maxWidth - gutter * 2
        LazyVerticalGrid(GridCells.Fixed(columns), state = scroll, modifier = Modifier.fillMaxSize()
            .onGloballyPositioned { segmentMotion.viewportTop = it.positionInRoot().y }
            .librarySegmentMotion(segmentMotion, created)
            .playerQualityBackdropSnapshot(editorBackdrop, editor.open),
            userScrollEnabled = !segmentMotion.moving,
            contentPadding = PaddingValues(start = gutter, end = gutter, top = gridTopPadding, bottom = bottomInset + 28.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item("profile", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.padding(bottom = 12.dp)) { QqProfileHeader(account, onOpenSettings) }
            }
            item("favorites", span = { GridItemSpan(maxLineSpan) }) {
                val open = {
                    if (!library.signedIn) entry()
                    else library.favoritePlaylist?.let(onOpenPlaylist) ?: onRefresh()
                }
                val presented = favorite.copy(title = "我喜欢",
                    subtitle = if (library.signedIn) "${favorite.count} 首歌曲" else "登录后同步")
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                    QqPlaylistCard(
                        playlist = presented,
                        onClick = open,
                        onPlay = { library.favoritePlaylist?.let(onPlayPlaylist) ?: open() },
                        modifier = Modifier.width(favoriteWidth),
                        width = 1280.dp,
                        artworkVersion = library.artworkVersions[favorite.id] ?: 0L,
                        sharedTransition = library.favoritePlaylist != null,
                        prominentTitle = true,
                        sourceKey = "qq-my-favorite:${favorite.id}",
                    )
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
                        IconButton(onClick = { if (library.signedIn) editor.show(null, Rect.Zero) else entry() }) {
                            Icon(Icons.Rounded.Add, "新建歌单")
                        }
                    }
                    QqPlaylistSegment(created, library.createdPlaylists.size, library.collectedPlaylists.size,
                        { switchSegment(true) }, { switchSegment(false) })
                }
            }
            items(displayedPlaylists, key = { "${created}:${it.id}" }) { playlist ->
                val presented = playlist.withQqLibrarySongCountSubtitle()
                val artworkVersion = library.artworkVersions[playlist.id] ?: 0L
                val removalProgress by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (departingId == playlist.id) 0f else 1f,
                    animationSpec = musicMotion(220),
                    label = "删除歌单淡出",
                )
                QqPlaylistCard(
                    playlist = presented,
                    onClick = { onOpenPlaylist(presented) },
                    onPlay = { onPlayPlaylist(presented) },
                    modifier = Modifier.fillMaxWidth().graphicsLayer {
                        alpha = if (hiddenId == playlist.id) 0f else removalProgress
                        scaleX = .94f + .06f * removalProgress
                        scaleY = scaleX
                    }.animateItem(
                        fadeInSpec = if (segmentMotion.moving) null else musicMotion(320),
                        fadeOutSpec = if (segmentMotion.moving || departingId == playlist.id) null else musicMotion(220),
                        placementSpec = if (segmentMotion.moving) null else musicSpring(),
                    ),
                    width = 1280.dp,
                    artworkVersion = artworkVersion,
                    sourceKey = "qq-my-library:$created:${playlist.id}",
                    onLongClick = if (created) { bounds ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        hiddenId = playlist.id
                        editor.show(presented, bounds, artworkVersion)
                    } else null,
                )
            }
            if (displayedPlaylists.isEmpty()) item("status", span = { GridItemSpan(maxLineSpan) }) {
                QqInlineMessage(when {
                    !library.signedIn -> "登录后，你的音乐收藏会出现在这里"
                    loading -> "正在同步歌单…"
                    else -> message ?: if (created) "还没有创建的歌单" else "还没有收藏的歌单"
                })
            }
            item("recent-play", span = { GridItemSpan(maxLineSpan) }) {
                QqRecentPlaySection(recentPlay, library.signedIn, onOpenPlaylist)
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
                if (action == PlaylistEditMode.Delete) pendingDeleteId = target?.id else onRefresh()
            },
            onRestored = { hiddenId = null },
            onClosed = {
                pendingDeleteId?.let { deletedId ->
                    pendingDeleteId = null
                    scope.launch {
                        departingId = deletedId
                        delay(if (ExperiencePreferences.options.reduceMotion) 0L else 220L)
                        locallyDeletedIds = locallyDeletedIds + deletedId
                        departingId = null
                        onRefresh()
                    }
                }
            },
        )
    }
}

internal fun qqLibraryColumns(width: Dp): Int = when { width >= 1000.dp -> 4; width >= 600.dp -> 3; else -> 2 }

internal fun MusicPlaylist.withQqLibrarySongCountSubtitle(): MusicPlaylist =
    copy(subtitle = "$count 首歌曲")
