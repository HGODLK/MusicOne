package com.musicone.sourceintegration

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.musicone.*
import com.musicone.source.sdk.*

/** 完整模板共用一套导航、封面交接和搜索上下文，嵌入与根层打开走同一路径。 */
@Composable
internal fun SourcePublicPageContent(request: SourcePageRequest, player: MusicPlaybackRuntime,
    modifier: Modifier, bottomInsetDp: Float, onClose: () -> Unit, interactive: Boolean = true,
    rootPage: Boolean = false) {
    val surface = LocalSourcePublicSurface.current
    val pageInteractive = interactive && (surface?.interactive != false) && (surface?.active != false)
    val source = remember(request.sourceId) { MusicSource.fromId(request.sourceId) }
    val controller = SourcePackageController.get(LocalContext.current)
    val installed by controller.installed.collectAsState()
    val instance = remember(source, installed) { controller.running(source.id) }
    val parentLifecycle = LocalLifecycleOwner.current
    val pageLifecycle = remember(parentLifecycle) { SourceSurfaceLifecycle(parentLifecycle) }
    val focus = LocalFocusManager.current
    var previouslyInteractive by remember(pageLifecycle) { mutableStateOf(pageInteractive) }
    SideEffect {
        pageLifecycle.setInteractive(pageInteractive)
        if (previouslyInteractive && !pageInteractive) focus.clearFocus(force = true)
        previouslyInteractive = pageInteractive
    }
    DisposableEffect(pageLifecycle) { onDispose { pageLifecycle.close() } }
    key(source, instance, request) {
    SourcePublicPageState(source, instance) {
        // 整页自建导航，其锚点与动效宿主都使用当前 Compose 根坐标；首页共享卡片仍保留外层偏移。
        CompositionLocalProvider(LocalLifecycleOwner provides pageLifecycle,
            LocalMotionRootOffset provides Offset.Zero,
            LocalMotionRootOffsetReader provides null) {
        // 同一根的弹出页已经继承消费链，只有独立 ComposeView 需要接回外层实际消费量。
        val sceneModifier = if (surface?.separateRoot == true) modifier.consumeWindowInsets(surface.consumedInsets) else modifier
        SourcePublicPageScene(request, player, sceneModifier, bottomInsetDp, onClose, pageInteractive, rootPage)
        }
    }
    }
}

@Composable
private fun SourcePublicPageScene(request: SourcePageRequest, player: MusicPlaybackRuntime,
    modifier: Modifier, bottomInsetDp: Float, onClose: () -> Unit, interactive: Boolean, rootPage: Boolean) {
    val source = remember(request.sourceId) { MusicSource.fromId(request.sourceId) }
    val context = LocalContext.current
    val controller = remember(context) { SourcePackageController.get(context) }
    val installed by controller.installed.collectAsState()
    val instance = remember(source, installed) { controller.running(source.id) }
    val platform: PlatformSettingsViewModel = viewModel()
    val chrome = checkNotNull(LocalSourcePublicSceneBridge.current) { "公共页面未绑定根导航宿主" }
    val surface = LocalSourcePublicSurface.current
    val platformState by platform.state.collectAsStateWithLifecycle()
    val catalog: MusicCatalogViewModel = viewModel()
    val catalogState by catalog.state.collectAsStateWithLifecycle()
    val library = rememberSourcePublicLibraryViewModel(source, instance)
    val prepareHiddenMy = rememberPrepareHiddenMyArtwork(
        rootPage && request is SourcePageRequest.My && surface?.separateRoot == true, library, interactive)
    // 一级嵌入“我的”隐藏时也交付目录给原卡片，封面只准备已有缓存。
    val libraryState by if (prepareHiddenMy) library.state.collectAsState() else library.state.collectAsStateWithLifecycle()
    val search: MainSourceSearchViewModel = viewModel()
    val searchState by search.state.collectAsStateWithLifecycle()
    val playback by player.state.collectAsStateWithLifecycle()
    val playSortedPlaylist = rememberSortedPlaylistPlay(platformState.account?.userId, player::playPlaylist)
    val playlists = rememberRecommendationNavigation()
    val entities = rememberEntityNavigation()
    val settings = chrome.settings
    val toolbar = rememberPlaylistToolbarOverlayState()
    val tools = rememberPlaylistFloatingToolsOverlayState()
    val settingsMotion = chrome.settingsMotion
    var settingsOrigin by remember { mutableStateOf(Rect.Zero) }
    val backdrop = rememberGraphicsLayer()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val searchMotion = rememberMainSourceSearchMotion(searchState, backdrop)
    val searchHomeGuard = remember(search, searchMotion) { MainSourceSearchHomeGuard(search, searchMotion) }
    val scene = remember(playlists, entities, searchMotion, searchHomeGuard, surface?.rootIdentity) {
        SourcePublicScene(playlists, entities, searchMotion, searchHomeGuard, surface?.rootIdentity)
    }
    val openSettings = { chrome.openSettings(scene, settingsOrigin, surface?.currentRootOffset ?: Offset.Zero) }
    BindSourcePublicScene(scene)
    BindSourcePublicArtworkReturn(scene, chrome, interactive)
    val inset = bottomInsetDp.dp
    val detailInset = chrome.measurements.detailInset
    val sourceBackdrop = rememberSourceBackdropUiController(source)
    // 一级页的滚动显隐与宿主共用；独立打开的公共整页仍保留自己的页头状态。
    val primaryHeader = if (rootPage) LocalPrimaryPageHeaderState.current else rememberPrimaryPageHeaderState()
    val latestClose by rememberUpdatedState(onClose)
    var initialized by remember(request) { mutableStateOf(false) }
    var dailyOpened by remember(request) { mutableStateOf(false) }
    var supplementalBack by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(instance) { if (instance == null && source != MusicSource.LOCAL) latestClose() }
    LaunchedEffect(source, platformState.sessionRevision) {
        val connected = platformState.sessionStatus == SessionStatus.CONNECTED
        catalog.configure(source, platformState.homeSessionRevision, connected)
        search.configure(platformState.sessionRevision, source)
    }
    val favoriteChanges = LocalMusicFavorites.current.state.changes
    LaunchedEffect(library, favoriteChanges) { library.syncFavoriteChanges(favoriteChanges) }
    LaunchedEffect(request) {
        when (request) {
            is SourcePageRequest.Search -> {
                search.configure(platformState.sessionRevision, source)
                search.open(); search.setQuery(request.query)
                if (request.submit) search.submit()
            }
            is SourcePageRequest.Collection -> playlists.open(request.collection.toMusicPlaylist())
            is SourcePageRequest.Artist -> entities.open(EntityTarget.Artist(MainSourceArtist(
                request.artist.reference.id, request.artist.reference.name, request.artist.artwork.url,
                source, request.artist.reference)))
            is SourcePageRequest.Album -> entities.open(EntityTarget.Album(request.album.asTemplatePlaylist(source)))
            else -> Unit
        }
        // 先让初始导航进入下一帧，再允许关闭观察，避免同帧尚未挂载就误报退出。
        withFrameNanos { }
        initialized = true
    }
    val close: () -> Unit = {
        when {
            entities.pages.isNotEmpty() -> entities.back()
            playlists.motion.mounted -> playlists.close()
            settings.destination != SettingsDestination.CLOSED -> settings.back()
            searchState.opened -> search.back()
            supplementalBack != null -> supplementalBack?.invoke()
            else -> latestClose()
        }
    }
    BackHandler(enabled = interactive, onBack = close)
    LaunchedEffect(initialized, dailyOpened, playlists.playlist, playlists.motion.mounted, entities.pages.size, searchState.opened,
        searchMotion.mounted, settings.destination, settingsMotion.mounted) {
        if (!initialized) return@LaunchedEffect
        val finished = when (request) {
            is SourcePageRequest.Collection -> playlists.playlist == null && !playlists.motion.mounted
            is SourcePageRequest.DailyRecommendations -> dailyOpened && playlists.playlist == null && !playlists.motion.mounted
            is SourcePageRequest.Artist, is SourcePageRequest.Album -> entities.pages.isEmpty()
            is SourcePageRequest.Search -> !searchState.opened && !searchMotion.mounted
            else -> false
        }
        if (finished) latestClose()
    }
    CompositionLocalProvider(LocalEntityNavigation provides entities,
        LocalArtworkEntrancePolicy provides LocalArtworkEntrancePolicy.current.let { policy ->
            if (prepareHiddenMy) policy.copy(cacheOnly = true) else policy
        },
        LocalPrimaryPageHeaderState provides primaryHeader,
        LocalSourceBackdropVisual provides sourceBackdrop.visual.takeIf { sourceBackdrop.hasCustomGlobal },
        LocalMyPageVisible provides (request is SourcePageRequest.My && interactive),
        LocalAccountEntry provides { platform.startLogin(source) },
        LocalPlaylistMotion provides playlists.motion, LocalPlaylistCardTransition provides playlists.cards,
        LocalMainSourceSearchMotion provides searchMotion, LocalUnifiedBack provides true,
        LocalEntityActive provides entities.pages.isEmpty(),
        LocalRecommendationVisible provides (entities.pages.isEmpty() && !playlists.motion.mounted)) {
    PlatformMusicTheme(source, neutral = platformState.sessionStatus != SessionStatus.CONNECTED) {
        BindSourcePublicSceneHeader(scene, request, source, backdrop, bounds, search::open, openSettings,
            onSettingsBounds = { settingsOrigin = it }, rootPage = rootPage)
        BindSourcePublicSceneBack(scene, toolbar, searchState, search, backdrop, bounds)
        Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Box(Modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInRoot() }.drawWithContent {
                backdrop.record { this@drawWithContent.drawContent() }; drawLayer(backdrop)
            }) {
                when (request) {
                    is SourcePageRequest.Home -> PlatformHomeScreen(source, playback.copy(page = MusicOnePage.HOME),
                        catalogState, player, platformState.homeSessionRevision, platformState.sessionStatus, inset,
                        { platform.startLogin(source) }, playlists::open, playlists::open,
                        { catalog.loadPlaylist(it, playSortedPlaylist) }, catalog::refreshRecommendations,
                        catalog::refreshRecommendedTracks)
                    is SourcePageRequest.My -> SourcePublicMyPageState(source, instance) {
                        PlatformMyScreen(platformState.account, libraryState, inset,
                            openSettings, playlists::open, library::refresh,
                            { catalog.loadPlaylist(it, playSortedPlaylist) }, source)
                    }
                    is SourcePageRequest.Supplemental -> SupplementalSourceScreen(source.id, inset, latestClose,
                        { supplementalBack = it }, initialTarget = request.target)
                    is SourcePageRequest.Authentication -> if (instance?.entry?.supplementalPageProviderOrNull() != null) {
                        SupplementalSourceScreen(source.id, inset, latestClose, { supplementalBack = it },
                            initialTarget = SourceSupplementalTarget.Account)
                    } else SourcePublicAuthentication(source.id, inset, latestClose, interactive = interactive)
                    is SourcePageRequest.Settings -> if (instance?.entry?.supplementalPageProviderOrNull() != null) {
                        SupplementalSourceScreen(source.id, inset, latestClose, { supplementalBack = it },
                            initialPage = SourceSupplementalPage.SETTINGS)
                    } else SourcePublicAuthentication(source.id, inset, latestClose, mode = "settings", interactive = interactive)
                    is SourcePageRequest.DailyRecommendations, is SourcePageRequest.Recent,
                    is SourcePageRequest.Similar -> SourcePublicDiscoveryContent(request, catalogState, player,
                        inset, { dailyOpened = true; playlists.open(it) }, latestClose)
                    else -> Unit
                }
                MainSourceSearchResults(searchState, search, searchMotion, playback, detailInset, player::playTrackNext,
                    playlists::open, playlists.motion.mounted || entities.pages.isNotEmpty())
                RecommendationDestination(playlists, playback, player, detailInset, catalogState.loadingPlaylistId,
                    catalogState.actionMessage, libraryState, platformState.account?.userId, library::toggleCollected,
                    { playlist, changed -> library.refreshAfterPlaylistReturn(playlist.id, changed) }, toolbar, tools,
                    sourceBackdrop.playlistRevision, { sourceBackdrop.choosePlaylist(it.id) },
                    { sourceBackdrop.restorePlaylist(it.id) })
            }
            EntityPageHost(entities, playback, player, detailInset, libraryState.createdPlaylists, { library.refresh() })
            if (!rootPage && isSourceRootPageRequest(request) && entities.pages.isEmpty()) {
                if (!playlists.motion.mounted && !searchState.opened && settings.destination == SettingsDestination.CLOSED) {
                    IconButton(onClick = latestClose,
                        modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 10.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回音源页面")
                    }
                }
            }
            MainSourceSearchMenu(searchState, search, searchMotion, backdrop, bounds,
                playlists.motion.mounted || entities.pages.isNotEmpty())
            PlaylistToolbarOverlay(toolbar, playlists.motion, backdrop, bounds, Modifier.align(Alignment.TopCenter))
            UnifiedPlaylistFloatingTools(tools, playlists.motion, entities, backdrop, bounds, detailInset,
                Modifier.align(Alignment.BottomEnd))
            sourceBackdrop.dialogContent()
        }
    }
    }
}
