package com.musicone

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import kotlinx.coroutines.launch
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
internal fun MainSourceMyScreen(
    account: MusicAccount?, library: MainSourceLibraryUiState, bottomInset: Dp,
    onOpenSettings: () -> Unit, onOpenPlaylist: (MusicPlaylist) -> Unit, onRefresh: () -> Unit,
    onPlayPlaylist: (MusicPlaylist) -> Unit,
    requestedSource: MusicSource? = null,
) {
    val recentPlayViewModel: MainSourceRecentPlayViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val recentPlay by recentPlayViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val offline by remember(context) { OfflinePlaybackAvailability.get(context) }.state.collectAsStateWithLifecycle()
    val source = requestedSource ?: account?.source ?: PlatformPreferences(context).selectedSource()
    val sourceEntry = com.musicone.sourceintegration.SourcePackageController.get(context).running(source.id)?.entry
    val showRecentPlay = (sourceEntry?.discovery as? com.musicone.source.sdk.SourceMySectionPresentation)
        ?.showRecentInMy != false
    val favoriteAccess = mainSourceFavoriteAccess(library.signedIn, source.requiresAccount,
        sourceEntry?.authentication != null,
        com.musicone.source.sdk.SourceLibrarySection.FAVORITE_TRACKS in sourceEntry?.collections?.librarySections.orEmpty())
    // 不提供登录的音源没有账号，空状态按真实内容显示，不再提示登录。
    val loginFree = source.loginFree
    LaunchedEffect(source.id) { recentPlayViewModel.configure(source) }
    val scope = rememberCoroutineScope()
    val musicLibraryUi = LocalMusicLibraryUi.current
    val editor = remember(source) { MainSourcePlaylistEditorState(PlatformPreferences(context), scope, musicLibraryUi.store,
        context.applicationContext, requestedSource = source) }
    var host by remember { mutableStateOf(Rect.Zero) }
    var hiddenId by remember { mutableStateOf<String?>(null) }
    val removals = remember(account?.userId) { PlaylistCardRemovalState() }
    val selection = remember(source, account?.userId) { PlaylistCardSelection() }
    val selectionHost = LocalPlaylistSelectionBarHost.current
    val editorBackdrop = rememberGraphicsLayer()
    var savedSection by rememberSaveable { mutableIntStateOf(MY_SECTION_CREATED) }
    // 音源未声明的创建／收藏分区不显示，只剩本地时连分段按钮一起隐藏。
    val sections = mainSourceLibrarySections(sourceEntry?.collections?.librarySections.orEmpty())
    val section = sections.resolveSection(savedSection)
    val created = section == MY_SECTION_CREATED
    val localSection = section == MY_SECTION_LOCAL
    val scroll = rememberLazyGridState()
    val gridTopPadding = 88.dp
    val headerInset = with(LocalDensity.current) { gridTopPadding.toPx() }
    val (segmentMotion, switchSegment) = rememberLibrarySegmentSwitch(
        section, scroll, headerInset,
    ) { savedSection = it }
    val playlists = if (localSection) musicLibraryUi.snapshot.localPlaylists else if (created) library.createdPlaylists
        else rememberPlaylistReturnCards(library.collectedPlaylists, "${source.id}:${account?.userId.orEmpty()}",
            "${source.id}:my-library:false:")
    val loading = if (created) library.loadingCreated else library.loadingCollected
    val message = if (created) library.createdMessage else library.collectedMessage
    val entry = LocalAccountEntry.current ?: onOpenSettings
    val favorite = library.favoritePlaylist ?: MusicPlaylist("${source.id}:favorites", source,
        "我喜欢", "", "", 0, 0xFFE6B8C8, 0xFF98677D, "喜", emptyList())
    val availablePlaylists = removals.present(playlists.filterNot { it.id == favorite.id })
    val reorder = rememberPlaylistCardReorder(scroll, availablePlaylists,
        if (localSection) "local" else "${source.name.lowercase()}:${account?.userId.orEmpty()}:$section", localSection)
    val displayedPlaylists = reorder.arrange(availablePlaylists)
    // 卡片多选：创建分区要音源声明了删除，收藏分区要音源支持收藏歌单，本地分区始终可用。
    val canBatch = when {
        localSection -> true
        created -> library.signedIn && com.musicone.source.sdk.SourceCollectionOperation.DELETE in
            sourceEntry?.collectionEditing?.supportedOperations.orEmpty()
        else -> library.signedIn && sourceEntry?.favorites?.supportsCollections == true
    }
    val selectableIds = displayedPlaylists.map { it.id }.filter { it != PHONE_STORAGE_PLAYLIST_ID }
    LaunchedEffect(section) { selection.close() }
    LaunchedEffect(selectableIds) { selection.retain(selectableIds) }
    // 编辑面板或多选期间不能左右切到首页。
    LockRootPageSwipe(editor.open || selection.active)
    val myVisible = LocalMyPageVisible.current
    ObserveMainSourceMyLibraryRefresh(source.id to account?.userId,
        library.signedIn || favoriteAccess == MainSourceFavoriteAccess.AVAILABLE,
        myVisible && LocalRecommendationVisible.current, onRefresh)
    ObserveMainSourceRecentPlayVisibility(recentPlayViewModel, myVisible && LocalRecommendationVisible.current)
    LaunchedEffect(library.signedIn, myVisible) {
        if (library.signedIn && myVisible) recentPlayViewModel.ensureLoaded(force = true)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { recentPlayViewModel.ensureLoaded(force = true) }
    ReportPrimaryHeaderScroll(MusicOnePage.MY, scroll)
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { host = it.boundsInRoot() }, contentAlignment = Alignment.TopCenter) {
        val columns = mainSourceLibraryColumns(maxWidth)
        val gutter = maxOf(if (maxWidth >= 600.dp) 32.dp else 20.dp, (maxWidth - 1120.dp) / 2)
        val favoriteWidth = if (maxWidth >= 600.dp)
            (maxWidth - gutter * 2 - 16.dp * (columns - 1)) / columns else maxWidth - gutter * 2
        val pairedWidth = if (maxWidth >= 600.dp) favoriteWidth * 1.08f else null
        val cardSide = (maxWidth - gutter * 2 - 16.dp * (columns - 1)) / columns
        val trailingSpace = key(section) { playlistGridTrailingSpace(displayedPlaylists.size, columns, cardSide) }
        SideEffect { selection.slots = playlistGridSlots(listOf(displayedPlaylists.map { it.id }), columns) }
        LazyVerticalGrid(GridCells.Fixed(columns), state = scroll, modifier = Modifier.fillMaxSize()
            .onGloballyPositioned { segmentMotion.viewportTop = it.positionInRoot().y }
            // 多选、编辑、拖动排序期间不响应左右滑动切分区；切换动画中可以接着滑，由切换函数打断续接。
            .librarySegmentSwipe(sections, section, !reorder.dragging && !editor.open && !selection.active,
                { segmentMotion.headerTop - segmentMotion.viewportTop }, switchSegment)
            // 过渡中只吞掉按下，卡片点不开，但横滑仍能接手（原先整页盖层连滑动一起挡掉了）。
            .blockLibraryTapsWhile(segmentMotion.moving)
            .librarySegmentMotion(segmentMotion, section)
            .playerQualityBackdropSnapshot(editorBackdrop, editor.open),
            userScrollEnabled = !segmentMotion.moving && !reorder.dragging && !editor.open,
            contentPadding = PaddingValues(start = gutter, end = gutter, top = gridTopPadding, bottom = bottomInset + 28.dp + trailingSpace),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item("profile", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.animateContentSize(musicSpring()).padding(bottom = 12.dp)) { MainSourceProfileHeader(account, onOpenSettings, loginFreeName = source.label.takeIf { loginFree && account == null }) }
            }
            item("favorites", span = { GridItemSpan(maxLineSpan) }) {
                val open = {
                    when (favoriteAccess) {
                        MainSourceFavoriteAccess.UNSUPPORTED -> android.widget.Toast.makeText(context,
                            "当前音源不支持我喜欢", android.widget.Toast.LENGTH_SHORT).show()
                        MainSourceFavoriteAccess.LOGIN_REQUIRED -> entry()
                        MainSourceFavoriteAccess.AVAILABLE -> {
                            if (!offline.online) onOpenPlaylist(mainSourceOfflineFavorites(favorite))
                            else library.favoritePlaylist?.let(onOpenPlaylist) ?: onRefresh()
                        }
                    }
                }
                val favoriteReady = favoriteAccess == MainSourceFavoriteAccess.AVAILABLE && library.favoritePlaylist != null
                val presented = favorite.copy(title = "我喜欢",
                    subtitle = when (favoriteAccess) {
                        MainSourceFavoriteAccess.UNSUPPORTED -> "当前音源不支持"
                        MainSourceFavoriteAccess.LOGIN_REQUIRED -> "登录后同步"
                        MainSourceFavoriteAccess.AVAILABLE -> when {
                            favoriteReady -> "${favorite.count} 首歌曲"
                            !offline.online -> "离线，点击查看已保存的收藏"
                            library.loadingFavorites -> ""
                            else -> library.favoritesMessage ?: ""
                        }
                    })
                // 两张卡片占用固定槽位，横滑溢出由整页视口裁剪，不能在网格留白处提前截断。
                val cardOptions = ExperiencePreferences.options
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                    val (firstCard, secondCard) = mainSourceLibraryCards(presented, musicLibraryUi.snapshot.unified,
                        favoriteAccess, cardOptions.hideUnifiedPlaylist)
                    PairedLibraryCards(firstCard, secondCard,
                        onOpen = { if (it.id == favorite.id) open() else onOpenPlaylist(it) },
                        onPlay = { if (it.id == favorite.id) {
                            if (favoriteReady) onPlayPlaylist(if (offline.online) favorite else mainSourceOfflineFavorites(favorite)) else open()
                        } else onPlayPlaylist(it) },
                        tabletCardWidth = pairedWidth, firstSharedTransition = firstCard.id != favorite.id || favoriteReady,
                        phoneSideBySide = cardOptions.phoneSideBySideLibraryCards)
                }
            }
            if (supportsArtistFollowing(context, source)) item("followed-artists", span = { GridItemSpan(maxLineSpan) }) {
                ArtistFollowingDirectory(source, account?.userId)
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
                        // 平时是多选图标和新建按钮，多选时整组原位淡出，“全选”在同一槽位淡入。
                        // 无需登录的音源没有账号歌单可建，只在本地分区保留新建。
                        val showCreate = localSection || (!loginFree && MY_SECTION_CREATED in sections)
                        PlaylistSectionSelectAll(selection, selectableIds, { selection.begin(null) }.takeIf { canBatch && selectableIds.isNotEmpty() },
                            trailing = if (showCreate) { {
                                IconButton(onClick = { if (localSection) editor.show(null, Rect.Zero, local = true) else if (library.signedIn) editor.show(null, Rect.Zero) else entry() }) {
                                    Icon(Icons.Rounded.Add, "新建歌单")
                                }
                            } } else null)
                    }
                    if (sections.size > 1) LibrarySectionTabs(sections.indexOf(section),
                        listOf("创建 ${library.createdPlaylists.size}", "收藏 ${library.collectedPlaylists.size}", "本地 ${musicLibraryUi.snapshot.localPlaylists.size}").let { all -> sections.map(all::get) }) { switchSegment(sections[it]) }
                }
            }
            items(displayedPlaylists, key = { it.id }) { playlist ->
                val presented = playlist.withMainSourceLibrarySongCountSubtitle()
                val artworkVersion = library.artworkVersions[playlist.id] ?: 0L
                SelectablePlaylistCard(selection, playlist.id, playlist.id != PHONE_STORAGE_PLAYLIST_ID,
                    Modifier.fillMaxWidth().playlistCardLift(reorder, playlist.id).graphicsLayer {
                        alpha = if (hiddenId == playlist.id || removals.hidden(playlist.id)) 0f else 1f
                    }.animateItem(
                        fadeInSpec = if (segmentMotion.moving) null else musicMotion(320),
                        fadeOutSpec = if (segmentMotion.moving || removals.hidden(playlist.id)) null else musicMotion(220),
                        placementSpec = if (segmentMotion.moving || reorder.activeId == playlist.id) null else musicSpring(),
                    )) { hintVisibility ->
                MainSourcePlaylistCard(
                    playlist = presented,
                    onClick = { onOpenPlaylist(presented) },
                    onPlay = { onPlayPlaylist(presented) },
                    modifier = Modifier.fillMaxWidth(),
                    width = 1280.dp,
                    artworkVersion = artworkVersion,
                    sourceKey = if (localSection) "library-user:${playlist.id}" else "${source.id}:my-library:$created:${playlist.id}",
                    freezeArtwork = hiddenId == playlist.id,
                    // 多选期间停用拖动排序；播放按钮淡出但保留位置，点击由多选层接管。
                    reorderState = reorder.takeUnless { selection.active },
                    playVisibility = hintVisibility,
                    onLongClick = if (created || localSection) { origin ->
                        removals.hold(playlist, displayedPlaylists.indexOfFirst { it.id == playlist.id })
                        hiddenId = playlist.id
                        editor.show(presented, origin.bounds, artworkVersion, origin.artwork, local = localSection,
                            currentBounds = origin.currentBounds)
                    } else null,
                )
                }
            }
            if (displayedPlaylists.isEmpty() && (localSection || !library.signedIn || !loading)) item("status", span = { GridItemSpan(maxLineSpan) }) {
                MainSourceInlineMessage(when {
                    localSection -> "还没有本地歌单，点击右上角创建"
                    !library.signedIn && !loginFree -> "登录后，你的音乐收藏会出现在这里"

                    else -> message ?: if (created) "还没有创建的歌单" else "还没有收藏的歌单"
                })
            }
            if (showRecentPlay && !recentPlay.unsupported) item("recent-play", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = musicSpring())
                    .animateContentSize(musicSpring())) {
                    MainSourceRecentPlaySection(recentPlay, library.signedIn || loginFree, onOpenPlaylist)
                }
            }
        }
        PlaylistCardSelectionBar(selection, selectableIds, if (created || localSection) "删除" else "取消收藏") {
            val targets = displayedPlaylists.filter { it.id in selection.ids }
            val names = playlistBatchNames(targets)
            // 云端批量：逐个请求，操作条显示进度；全部成功退出多选，否则只保留失败项。
            // 已完成的卡片先淡出缩小，再刷新列表让其余卡片补位。
            fun runRemote(verb: String, action: suspend ((Int, Int) -> Unit) -> CollectionBatchResult) {
                scope.launch {
                    selection.busyText = "正在$verb…"
                    var finished = emptyList<String>()
                    val text = try {
                        val result = action { index, total -> selection.busyText = "正在$verb ${index + 1}/$total" }
                        selection.busyText = null
                        finished = result.done.map { it.id }
                        selection.markLeaving(finished)
                        if (result.failed.isEmpty()) selection.close() else selection.keepOnly(result.failed.map { it.id })
                        result.summary(verb)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        failure.asUserMessage()
                    } finally {
                        selection.busyText = null
                    }
                    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
                    if (finished.isNotEmpty()) kotlinx.coroutines.delay(PlaylistCardLeaveMillis)
                    onRefresh()
                    // 列表迟迟没有刷新时恢复显示，不让卡片一直隐身。
                    if (finished.isNotEmpty()) { kotlinx.coroutines.delay(4_000); selection.clearLeaving() }
                }
            }
            selectionHost.confirm = when {
                localSection -> PlaylistBatchConfirm("删除 ${targets.size} 个歌单？", names + "\n只删除歌单，原音乐文件会保留。", "删除") {
                    musicLibraryUi.work("正在删除歌单…") {
                        val ids = targets.map { it.id }
                        selection.close()
                        selection.markLeaving(ids)
                        kotlinx.coroutines.delay(PlaylistCardLeaveMillis)
                        val removed = try { musicLibraryUi.store.deletePlaylists(ids) }
                            catch (failure: Exception) { selection.clearLeaving(); throw failure }
                        musicLibraryUi.message = "已删除 $removed 个歌单"
                    }
                }
                created -> PlaylistBatchConfirm("删除 ${targets.size} 个歌单？",
                    names + "\n将从你的${source.label}账号中永久删除，无法恢复。", "删除") {
                    runRemote("删除") { progress -> deleteSourceCollections(context, source, targets, progress) }
                }
                else -> PlaylistBatchConfirm("取消收藏 ${targets.size} 个歌单？", names + "\n歌单本身不会被删除，可以重新收藏。", "取消收藏") {
                    runRemote("取消收藏") { progress -> unfavoriteSourceCollections(context, source, account?.userId, targets, progress) }
                }
            }
        }
        MainSourcePlaylistEditorOverlay(
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

internal fun mainSourceLibraryColumns(width: Dp): Int = when { width >= 1000.dp -> 4; width >= 600.dp -> 3; else -> 2 }

internal fun MusicPlaylist.withMainSourceLibrarySongCountSubtitle(): MusicPlaylist =
    copy(subtitle = "$count 首歌曲")
