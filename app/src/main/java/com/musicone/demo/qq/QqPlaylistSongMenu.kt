package com.musicone.demo

import androidx.compose.material3.MaterialTheme

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 歌曲菜单独立持有状态与采样层，不将写入和菜单动画塞进页面主组件。 */
@Composable
internal fun QqPlaylistSongMenuHost(
    playlist: MusicPlaylist,
    createdPlaylists: List<MusicPlaylist>,
    bottomInset: Dp,
    onRemoved: (MusicTrack) -> Unit,
    onChanged: (MusicPlaylist) -> Unit,
    onFavorite: (MusicTrack) -> Unit,
    addOnly: Boolean = false,
    relatedOnly: Boolean = false,
    hasLyrics: Boolean = false,
    avoidQueueRow: Boolean = false,
    queueMenu: Boolean = false,
    liveBackdrop: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val musicLibraryUi = LocalMusicLibraryUi.current
    val menu = remember(playlist.id) { QqPlaylistSongMenuState(scope, PlatformPreferences(context), library = musicLibraryUi.store) }
    val selection = remember(playlist.id) { PlaylistMultiSelection() }
    val favoriteActions = LocalMusicFavorites.current
    val queueActions = LocalPlaybackQueueActions.current
    val favorites = playlist.isAccountFavorites()
    val removable = !addOnly && !relatedOnly && (favorites ||
        (playlist.isManagedLibrary && musicLibraryUi.snapshot.manualAdditionTargets().any { it.id == playlist.id }) || createdPlaylists.any { it.id == playlist.id } ||
        (playlist.source in listOf(MusicSource.NETEASE, MusicSource.KUGOU) && playlist.isOwned))
    val removalModel: PlaylistRemovalViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val removalBatch = if (removable && !favorites) removalModel.batch(playlist) else null
    val removalActions = if (removalBatch == null) PlaylistRemovalActions() else PlaylistRemovalActions(
        playlist.id, removalBatch.ids, removalBatch::set)
    SideEffect { menu.removals = removalActions }
    DisposableEffect(removalBatch) {
        onDispose { removalBatch?.let { removalModel.flush(it, onChanged) { error -> musicLibraryUi.message = error } } }
    }
    BackHandler(enabled = selection.active && !menu.expanded, onBack = selection::close)
    val frame = LocalEntityFrame.current
    val entities = LocalEntityNavigation.current
    SideEffect { if (!addOnly) { if (frame != null) frame.menu = menu else if (menu.expanded) entities?.rootMenu = menu } }
    DisposableEffect(menu) { onDispose { if (entities?.rootMenu === menu) entities.rootMenu = null } }
    val backdrop = rememberGraphicsLayer()
    val surface = rememberGraphicsLayer()
    val artistArtwork = remember(menu) { MenuArtistArtworkState() }
    SideEffect {
        menu.surfaceLayer = surface
        menu.artistArtworkState = artistArtwork
    }
    DisposableEffect(menu, artistArtwork) {
        onDispose {
            if (menu.artistArtworkState === artistArtwork) menu.artistArtworkState = null
        }
    }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    SideEffect { menu.backdropLayer = backdrop; menu.backdropBounds = bounds; menu.liveBackdrop = liveBackdrop }
    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalSongMenuWorking provides menu.busy, LocalPlaylistRemovals provides removalActions, LocalPlaylistMultiSelection provides selection, LocalQqPlaylistSongMenu provides menu.takeIf { playlist.supportsSongMenu || addOnly },
        LocalMenuArtistArtwork provides artistArtwork,
        LocalPlaylistRemovalLabel provides if (!removable) null else if (favorites) "移除喜欢" else "移出歌单") {
        BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInRoot() }) {
            Box(Modifier.fillMaxSize().playerQualityBackdropSnapshot(backdrop, menu.expanded,
                captureContinuously = liveBackdrop)) { content() }
            val baseWidth = minOf(360.dp, maxWidth - 24.dp)
            val expandedWidth = minOf(if (menu.editing) 420.dp else 360.dp, maxWidth - 24.dp)
            val keyboardInset = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
            val usableHeight = maxHeight - maxOf(bottomInset, keyboardInset)
            val availableHeight = (usableHeight - 84.dp).coerceAtLeast(100.dp)
            val width = if (menu.editing) expandedWidth else baseWidth
            val height = if (relatedOnly) with(density) { menuSize.height.toDp() }.takeIf { it > 0.dp }
                ?: minOf(176.dp, availableHeight) else minOf(320.dp, availableHeight)
            val targetX = if (menu.editing) (maxWidth - width) / 2 else with(density) { (menu.anchor.right - bounds.left).toDp() - width }
                .coerceIn(12.dp, (maxWidth - width - 12.dp).coerceAtLeast(12.dp))
            val targetY = if (menu.editing) ((usableHeight - minOf(560.dp, availableHeight)) / 2).coerceAtLeast(12.dp) else with(density) { (menu.anchor.top - bounds.top).toDp() - height - 8.dp }
                .coerceIn(60.dp, (usableHeight - height - 12.dp).coerceAtLeast(60.dp))
            val x by animateDpAsState(targetX, musicMotion(320), label = "歌曲菜单横向变换")
            val y by animateDpAsState(targetY, musicMotion(320), label = "歌曲菜单纵向变换")
            val contentHeight = if (avoidQueueRow) maxHeight else if (relatedOnly) availableHeight else
                (usableHeight - y - 12.dp).coerceAtLeast(80.dp)
            val placement = if (avoidQueueRow) Modifier.queueSongMenuPlacement(menu.anchor, bounds) else Modifier.offset(x, y)
            AnimatedVisibility(menu.expanded,
                enter = fadeIn(musicMotion(220)) + if (avoidQueueRow) EnterTransition.None else scaleIn(musicMotion(280), initialScale = .84f,
                    transformOrigin = qualityMenuTransformOrigin(menu.anchor.center, bounds)),
                exit = fadeOut(musicMotion(180)) + if (avoidQueueRow) ExitTransition.None else scaleOut(musicMotion(220), targetScale = .92f,
                    transformOrigin = qualityMenuTransformOrigin(menu.anchor.center, bounds))) {
                Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { menu.dismiss() }) {
                    MusicOneBackdropGlass(
                        backdropLayer = backdrop, backdropBounds = bounds,
                        modifier = placement.onSizeChanged { menuSize = it }
                            .onGloballyPositioned { menu.surfaceBounds = it.boundsInRoot() }
                            .drawWithContent {
                                surface.record { this@drawWithContent.drawContent() }
                                drawLayer(surface)
                            }
                            .clickable(remember { MutableInteractionSource() }, null) {},
                        shape = RoundedCornerShape(28.dp), blurRadius = 24.dp,
                        containerColor = if (relatedOnly || addOnly) MaterialTheme.colorScheme.surface.copy(alpha = .72f) else playlistMenuContainer,
                        fallbackColor = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surface.copy(alpha = .2f)),
                    ) {
                        AnimatedContent(Triple(menu.choosing, menu.choosingArtist, menu.editing), label = "歌曲菜单展开收回",
                            transitionSpec = {
                                val direction = if (targetState.first || targetState.second || targetState.third) 1 else -1
                                ((fadeIn(musicMotion(240)) + slideInVertically(musicMotion(320)) { direction * it / 8 }) togetherWith
                                    (fadeOut(musicMotion(160)) + slideOutVertically(musicMotion(240)) { -direction * it / 8 }))
                                    .using(SizeTransform(clip = true, sizeAnimationSpec = { _, _ -> musicMotion(320) }))
                            }) { phase ->
                            val choosing = phase.first
                            Column(Modifier.width(if (choosing || phase.third) expandedWidth else baseWidth)
                                .heightIn(max = contentHeight).verticalScroll(rememberScrollState()).padding(14.dp)
                                ) {
                                Text(if (phase.third) "编辑歌曲资料" else if (choosing) { if (menu.batchTracks.isEmpty()) "添加到歌单" else "添加 ${menu.batchTracks.size} 首到歌单" } else if (menu.batchTracks.isNotEmpty()) "已选 ${menu.batchTracks.size} 首歌曲" else menu.track?.title.orEmpty(),
                                    color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(10.dp))
                                menu.message?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                                    modifier = Modifier.padding(10.dp)) }
                                if (phase.third) {
                                    LibraryTrackEditor(menu)
                                } else if (choosing) {
                                    SongMenuRow("返回", enabled = !menu.busy) { menu.back() }
                                    menu.targets.forEach { target ->
                                        PlaylistAddMenuRow(target.title + if (!target.isManagedLibrary && menu.batchTracks.any { it.source != target.source }) "（可添加 ${menu.batchTracks.count { it.source == target.source }} 首）" else "",
                                            target, menu, onChanged)
                                    }
                                    if (menu.targets.isEmpty() && !menu.busy) SongMenuRow("重新加载", true) { menu.choose() }
                                } else {
                                    if (removable && !phase.second) SongMenuRow(if (favorites) "移除我喜欢" else "移出当前歌单", !menu.busy) {
                                        if (favorites && menu.batchTracks.isNotEmpty()) {
                                            val removals = menu.batchTracks.filter { it.id !in favoriteActions.state.deferredRemovalIds &&
                                                it.id !in favoriteActions.state.updating }
                                            removals.forEach(favoriteActions.toggleDeferredRemoval)
                                            selection.close()
                                            menu.dismiss()
                                        }
                                        else if (favorites) { menu.track?.let(onFavorite); menu.dismiss() }
                                        else {
                                            (menu.batchTracks.ifEmpty { listOfNotNull(menu.track) }).forEach { removalActions.set(it, true) }
                                            selection.close()
                                            menu.dismiss()
                                        }
                                    }
                                    if (!relatedOnly && !phase.second && menu.batchTracks.isEmpty() && queueActions != null) {
                                        SongMenuRow(if (queueMenu) "下一首播放" else "插入队列", !menu.busy) {
                                            menu.track?.let(queueActions.insertNext)
                                            menu.dismiss()
                                        }
                                        if (queueMenu) SongMenuRow("移出队列", !menu.busy) {
                                            val selected = menu.track
                                            menu.dismiss()
                                            selected?.let(queueActions.remove)
                                        }
                                    }
                                    if (!relatedOnly && !phase.second) SongMenuRow("添加到", !menu.busy) { menu.choose() }
                                    if (!phase.second && menu.batchTracks.isEmpty()) LibrarySongMenuActions(menu, playlist, playerMenu = relatedOnly, hasLyrics = hasLyrics)
                                    if (!addOnly && !menu.fromLyricsTitle && menu.batchTracks.isEmpty() && menu.track?.source == MusicSource.QQ) RelatedEntityMenu(
                                        menu,
                                        phase.second,
                                        artworkVisible = phase == Triple(menu.choosing, menu.choosingArtist, menu.editing),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            MenuArtistArtworkOverlay(artistArtwork, bounds, menu.expanded)
        }
    }
    // 在详情页之后注册，返回键优先收回子菜单，不直接关闭歌单。
    if (menu.expanded && (addOnly || relatedOnly || (LocalEntityActive.current && LocalPlayerMotion.current?.mounted != true))) BackHandler { menu.back() }
}

@Composable
internal fun SongMenuRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    SongMenuRow(label, enabled, trailing = {}, onClick = onClick)
}

@Composable
internal fun SongMenuRow(label: String, enabled: Boolean, trailing: @Composable () -> Unit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 15.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled || LocalSongMenuWorking.current) 1f else .38f), fontSize = 14.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
    }
}

internal val LocalSongMenuWorking = staticCompositionLocalOf { false }
