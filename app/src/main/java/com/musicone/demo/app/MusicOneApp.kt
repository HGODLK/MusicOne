package com.musicone.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun MusicOneApp(viewModel: MusicOneViewModel = viewModel()) {
    val pageBackground = androidx.compose.material3.MaterialTheme.colorScheme.background
    ExperiencePreferences.initialize(androidx.compose.ui.platform.LocalContext.current)
    val navigation by rememberAppPlaybackNavigation(viewModel.state)
    val platformViewModel: PlatformSettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val catalogViewModel: MusicCatalogViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val searchViewModel: MusicSearchViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val qqSearchViewModel: QqSearchViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val qqSearchState by qqSearchViewModel.state.collectAsStateWithLifecycle()
    val backdropLayer = rememberGraphicsLayer()
    val entitySceneLayer = rememberGraphicsLayer()
    val qqSearchMotion = rememberQqSearchMotion(qqSearchState, backdropLayer)
    val favoriteViewModel: MusicFavoriteViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val qqLibraryViewModel: QqLibraryViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val kugouLibraryViewModel: KugouLibraryViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val platformState by platformViewModel.state.collectAsStateWithLifecycle()
    val catalogState by catalogViewModel.state.collectAsStateWithLifecycle()
    val searchState by searchViewModel.state.collectAsStateWithLifecycle()
    val favoriteState by favoriteViewModel.state.collectAsStateWithLifecycle()
    val qqLibraryState by qqLibraryViewModel.state.collectAsStateWithLifecycle()
    val kugouLibraryState by kugouLibraryViewModel.state.collectAsStateWithLifecycle()
    val neteaseBackground = rememberNeteaseBackgroundUiController()
    val neteaseProfileVisual = neteaseBackground.visual
    val globalNeteaseBackground = platformState.selectedSource == MusicSource.NETEASE &&
        neteaseBackground.hasCustomGlobal
    val immersiveNeteaseSurface = platformState.selectedSource == MusicSource.NETEASE &&
        (globalNeteaseBackground ||
            navigation.page == MusicOnePage.MY && platformState.sessionStatus == SessionStatus.CONNECTED)
    val recommendationNavigation = rememberRecommendationNavigation()
    val settingsNavigation = rememberSettingsNavigation()
    val entityNavigation = rememberEntityNavigation()
    val pageInteraction = remember { PageInteractionActivity() }
    val settingsMotion = rememberPageMotion(false, enterAnimation = PlayerEnterAnimation, exitAnimation = PlayerReturnAnimation)
    LaunchedEffect(platformState.selectedSource, platformState.account?.userId) { entityNavigation.clear() }
    val barMeasurements = remember { BottomBarMeasurements() }
    val playerMotion = rememberPageMotion(navigation.playerExpanded,
        enterAnimation = PlayerEnterAnimation, exitAnimation = PlayerReturnAnimation)
    val state by rememberPagePlaybackState(viewModel.state, playerMotion)
    val playerControlHandoff = rememberPlayerControlHandoff()
    val homeLaunch = rememberHomeLaunchState()
    val playerRetention = rememberPlayerRetentionState()
    val searchNavigation = rememberSearchNavigation(playerMotion.mounted)
    val kugouSearchMotion = rememberKugouSearchMotion(
        searchNavigation.opened && platformState.selectedSource == MusicSource.KUGOU,
    )
    val kugouSearchShape = remember { KugouSearchRevealShape { kugouSearchMotion.reveal.value } }
    val qqSearchHomeGuard = rememberQqSearchHomeGuard(navigation.page, qqSearchViewModel, qqSearchMotion)
    val rootNavigation = rememberRootPageNavigation(navigation.page, viewModel::setPage,
        qqSearchHomeGuard::beforePageChange,
        qqSearchHomeGuard::onBottomBarContact,
        qqSearchHomeGuard::blocksBottomBarDrag)
    val primaryHeaderState = rememberPrimaryPageHeaderState()
    val playlistToolbarState = rememberPlaylistToolbarOverlayState()
    val playlistFloatingToolsState = rememberPlaylistFloatingToolsOverlayState()
    val playerSurfaceTexture = rememberGraphicsLayer()
    val playerAtmosphereMotion = rememberPlayerAtmosphereMotionState()
    var backdropBounds by remember { mutableStateOf(Rect.Zero) }
    var entitySceneBounds by remember { mutableStateOf(Rect.Zero) }

    LaunchedEffect(platformState.selectedSource) {
        viewModel.configureSource(platformState.selectedSource)
    }
    LaunchedEffect(platformState.selectedSource, platformState.sessionRevision, platformState.sessionStatus) {
        catalogViewModel.configure(
            platformState.selectedSource,
            platformState.sessionRevision,
            platformState.sessionStatus == SessionStatus.CONNECTED,
        )
        searchViewModel.configure(platformState.selectedSource, platformState.sessionRevision)
        if (platformState.selectedSource == MusicSource.QQ) qqSearchViewModel.configure(platformState.sessionRevision)
        else qqSearchViewModel.deactivate()
        favoriteViewModel.configure(platformState.selectedSource, platformState.sessionRevision)
        qqLibraryViewModel.configure(platformState.selectedSource, platformState.sessionRevision)
        kugouLibraryViewModel.configure(platformState.selectedSource, platformState.sessionRevision)
    }
    LaunchedEffect(platformState.sessionRevision) {
        viewModel.refreshQualityOptions()
        if (platformState.account != null && settingsNavigation.destination == SettingsDestination.PLATFORM_LOGIN) {
            settingsNavigation.back()
        }
    }
    LaunchedEffect(favoriteState.changes) {
        qqLibraryViewModel.syncFavoriteChanges(favoriteState.changes)
    }
    LaunchedEffect(navigation.page, platformState.selectedSource) {
        if (navigation.page == MusicOnePage.MY && platformState.selectedSource == MusicSource.QQ) {
            qqLibraryViewModel.refresh()
        }
        if (navigation.page == MusicOnePage.MY && platformState.selectedSource == MusicSource.KUGOU) {
            kugouLibraryViewModel.refresh()
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalPlaylistMotion provides recommendationNavigation.motion,
        LocalPlaylistCardTransition provides recommendationNavigation.cards,
        LocalPlayerMotion provides playerMotion,
        LocalQqSearchMotion provides qqSearchMotion,
        LocalEntityNavigation provides entityNavigation,
        LocalUnifiedBack provides true,
        LocalPageInteraction provides pageInteraction,
        LocalEntityActive provides entityNavigation.pages.isEmpty(),
        LocalRecommendationVisible provides (settingsNavigation.destination == SettingsDestination.CLOSED &&
            !searchNavigation.opened && !qqSearchMotion.mounted && !recommendationNavigation.motion.mounted && entityNavigation.pages.isEmpty()),
        LocalPlayerSurfaceTexture provides playerSurfaceTexture,
        LocalPlayerAtmosphereMotion provides playerAtmosphereMotion,
        LocalPlayerBackdropTexture provides backdropLayer,
        LocalPlayerControlHandoff provides playerControlHandoff,
        LocalPlayerBackdropBounds provides backdropBounds,
        LocalPrimaryPageHeaderState provides primaryHeaderState,
        LocalNeteaseGlobalVisual provides neteaseProfileVisual.takeIf { globalNeteaseBackground },
        LocalMusicFavorites provides MusicFavoriteActions(
            favoriteState,
            favoriteViewModel::toggle,
            favoriteViewModel::toggleDeferredRemoval,
            favoriteViewModel::flushDeferredRemovals,
        ),
    ) {
        PlatformMusicTheme(
            platformState.selectedSource,
            neutral = platformState.sessionStatus != SessionStatus.CONNECTED,
        ) {
        AccountEntryHost(platformState, settingsNavigation, platformViewModel::selectSource,
            platformViewModel::startLogin) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .observePageInteraction(pageInteraction)
                .background(pageBackground),
        ) {
            // 网易云模式下始终预挂载背景；首页的不透明底色会遮住它，
            // 翻到“我的”时由透明页面自然揭示，不在动画结束时突然插入。
            if (platformState.selectedSource == MusicSource.NETEASE) {
                NeteaseProfileBackdrop(
                    visual = neteaseProfileVisual,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            MiniPlayerBackdropScene(entitySceneLayer, entityNavigation.pages.isNotEmpty(),
                { entitySceneBounds = it }) {
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .onGloballyPositioned { backdropBounds = it.boundsInRoot() }
                    .drawWithContent {
                        val refreshBackdrop = shouldRefreshPlayerBackdrop(
                            playerMotion.phase, playerMotion.wantsOpen, playerMotion.value,
                        )
                        if (refreshBackdrop) {
                            backdropLayer.record {
                                drawRect(if (immersiveNeteaseSurface) Color.Transparent else pageBackground)
                                this@drawWithContent.drawContent()
                            }
                            drawLayer(backdropLayer)
                        } else {
                            drawContent()
                        }
                    },
            ) {
                run {
                    NeteaseAdaptiveForeground(neteaseProfileVisual.takeIf { globalNeteaseBackground }) {
                    Column(Modifier.fillMaxSize()) {
                        HorizontalPager(
                            state = rootNavigation.pagerState,
                            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                            beyondViewportPageCount = 1,
                            userScrollEnabled = false,
                            key = { MusicOnePage.entries[it] },
                        ) { page ->
                            // 每页单独裁剪并铺满底色，避免相邻页内容在边缘抗锯齿处泄漏一列像素。
                            val immersivePage = platformState.selectedSource == MusicSource.NETEASE &&
                                (globalNeteaseBackground || MusicOnePage.entries[page] == MusicOnePage.MY &&
                                    platformState.sessionStatus == SessionStatus.CONNECTED)
                            Box(
                                Modifier.fillMaxSize().clipToBounds()
                                    .background(if (immersivePage) Color.Transparent else pageBackground),
                            ) {
                                when (MusicOnePage.entries[page]) {
                                    MusicOnePage.HOME -> PlatformHomeScreen(
                                        source = platformState.selectedSource,
                                        state = state,
                                        catalog = catalogState,
                                        viewModel = viewModel,
                                        sessionRevision = platformState.sessionRevision,
                                        sessionStatus = platformState.sessionStatus,
                                        bottomInset = barMeasurements.homeInset,
                                        onChooseSource = { settingsNavigation.choosingSource = true },
                                        onOpenRecommendation = { playlist ->
                                            recommendationNavigation.open(playlist)
                                            catalogViewModel.loadPlaylist(playlist, recommendationNavigation::update)
                                        },
                                        onOpenLoadedPlaylist = recommendationNavigation::open,
                                        onPlayRecommendation = { playlist ->
                                            catalogViewModel.loadPlaylist(playlist, viewModel::playPlaylist)
                                        },
                                        onRefreshRecommendations = catalogViewModel::refreshRecommendations,
                                        onRefreshRecommendedTracks = catalogViewModel::refreshRecommendedTracks,
                                    )
                                    MusicOnePage.MY -> PlatformMyScreen(
                                        platformState.selectedSource,
                                        platformState.account,
                                        platformState.sessionStatus,
                                        catalogState,
                                        neteaseProfileVisual,
                                        qqLibraryState,
                                        kugouLibraryState,
                                        barMeasurements.homeInset,
                                        settingsNavigation::openSettings,
                                        { playlist ->
                                            recommendationNavigation.open(playlist)
                                            if (playlist.id != QQ_FAVORITES_PLAYLIST_ID) {
                                                catalogViewModel.loadPlaylist(playlist, recommendationNavigation::update)
                                            }
                                        },
                                        catalogViewModel::refreshLibrary,
                                        neteaseBackground.hasCustomGlobal,
                                        neteaseBackground.chooseGlobal,
                                        neteaseBackground.restoreGlobal,
                                        if (platformState.selectedSource == MusicSource.KUGOU)
                                            kugouLibraryViewModel::refresh else qqLibraryViewModel::refresh,
                                        { playlist ->
                                            if (playlist.id == QQ_FAVORITES_PLAYLIST_ID) viewModel.playPlaylist(playlist)
                                            else catalogViewModel.loadPlaylist(playlist, viewModel::playPlaylist)
                                        },
                                        { settingsNavigation.choosingSource = true },
                                    )
                                }
                            }
                        }
                    }
                    }
                    if (platformState.selectedSource == MusicSource.QQ) QqSearchResults(
                        qqSearchState, qqSearchViewModel, qqSearchMotion, state, barMeasurements.detailInset,
                        viewModel::playTrackNext,
                        { playlist ->
                            recommendationNavigation.open(playlist)
                            catalogViewModel.loadPlaylist(playlist, recommendationNavigation::update)
                        },
                        recommendationNavigation.motion.mounted || playerMotion.mounted,
                    )
                    RecommendationDestination(
                        recommendationNavigation, state, viewModel, barMeasurements.detailInset,
                        catalogState.loadingPlaylistId, catalogState.actionMessage,
                        qqLibraryState,
                        platformState.account?.userId.takeIf { platformState.selectedSource == MusicSource.QQ },
                        qqLibraryViewModel::toggleCollected,
                        { playlist, contentChanged ->
                            if (contentChanged) catalogViewModel.invalidatePlaylist(playlist.id)
                            qqLibraryViewModel.refreshAfterPlaylistReturn(playlist.id, contentChanged)
                        },
                        playlistToolbarState,
                        playlistFloatingToolsState,
                        neteaseBackground.playlistRevision,
                        { playlist -> neteaseBackground.choosePlaylist(playlist.id) },
                        { playlist -> neteaseBackground.restorePlaylist(playlist.id) },
                    )
                    if (searchNavigation.opened || kugouSearchMotion.mounted) {
                        Box(
                            Modifier.fillMaxSize().then(
                                if (platformState.selectedSource == MusicSource.KUGOU) {
                                    Modifier.graphicsLayer {
                                        shape = kugouSearchShape
                                        clip = true
                                    }
                                } else Modifier,
                            ),
                        ) {
                            MusicSearchScreen(
                                search = searchState,
                                player = state,
                                bottomInset = barMeasurements.detailInset,
                                onBack = searchNavigation::close,
                                onQueryChange = searchViewModel::setQuery,
                                onTrackClick = viewModel::playTrackNext,
                                animateLikeQq = platformState.selectedSource == MusicSource.KUGOU,
                                active = searchNavigation.opened,
                                onCollectionClick = { playlist ->
                                    searchNavigation.close()
                                    recommendationNavigation.open(playlist)
                                    catalogViewModel.loadPlaylist(playlist, recommendationNavigation::update)
                                },
                            )
                        }
                    }
                }
                SettingsPageHost(settingsNavigation, platformState, platformViewModel, settingsMotion, barMeasurements.detailInset,
                    listOfNotNull(qqLibraryState.favoritePlaylist) + qqLibraryState.createdPlaylists +
                        qqLibraryState.collectedPlaylists + listOfNotNull(kugouLibraryState.favoritePlaylist) +
                        kugouLibraryState.createdPlaylists + kugouLibraryState.collectedPlaylists +
                        catalogState.recommendations, viewModel::playTrackNext)
            }

            if (settingsNavigation.destination == SettingsDestination.CLOSED) {
                // 详情页玻璃仍采样根背景；详情层只进入迷你播放器的独立合成录制层。
                Box(Modifier.fillMaxSize().statusBarsPadding()) {
                    EntityPageHost(entityNavigation, state, viewModel, barMeasurements.detailInset,
                        qqLibraryState.createdPlaylists, { qqLibraryViewModel.refresh() })
                }
            }
            }

            run {
                if ((!searchNavigation.opened || kugouSearchMotion.mounted) && entityNavigation.pages.isEmpty()) {
                    PrimaryPageHeader(
                        page = navigation.page,
                        pagerPosition = { rootNavigation.position },
                        backdropLayer = backdropLayer,
                        backdropBounds = backdropBounds,
                        onSearch = {
                            if (platformState.sessionStatus != SessionStatus.CONNECTED) {
                                settingsNavigation.choosingSource = true
                            } else if (platformState.selectedSource == MusicSource.QQ) {
                                qqSearchViewModel.open()
                            } else {
                                searchNavigation.open()
                            }
                        },
                        onSettings = settingsNavigation::openSettings,
                        onSettingsBounds = { settingsNavigation.origin = it },
                        motionVisibility = {
                            primaryHeaderMotionVisibility(
                                recommendationNavigation.motion.value,
                                playerMotion.value,
                            )
                        },
                        modifier = Modifier.statusBarsPadding().align(Alignment.TopCenter),
                        searchProgress = { maxOf(qqSearchMotion.menu.value, kugouSearchMotion.reveal.value) },
                        settingsProgress = { settingsMotion.value },
                        settingsMounted = { settingsMotion.mounted },
                        contentColor = neteaseProfileVisual.foreground.takeIf { immersiveNeteaseSurface },
                    )
                }
                if (platformState.selectedSource == MusicSource.QQ) QqSearchMenu(
                    qqSearchState, qqSearchViewModel, qqSearchMotion, backdropLayer, backdropBounds,
                    recommendationNavigation.motion.mounted || playerMotion.mounted || entityNavigation.pages.isNotEmpty(),
                )
                BottomPlayerBar(
                    viewModel, recommendationNavigation.motion, playerMotion, barMeasurements,
                    backdropLayer, backdropBounds, playerControlHandoff, rootNavigation, entityNavigation,
                    entitySceneLayer, entitySceneBounds,
                    (platformState.selectedSource == MusicSource.KUGOU || !searchNavigation.opened) && entityNavigation.pages.isEmpty(),
                    animateQqPlayer = platformState.selectedSource == MusicSource.QQ,
                    immersiveVisual = neteaseProfileVisual.takeIf { immersiveNeteaseSurface },
                    searchProgress = { maxOf(qqSearchMotion.full.value, kugouSearchMotion.reveal.value, settingsMotion.value) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
                if (entityNavigation.pages.isEmpty()) PlaylistToolbarOverlay(
                    state = playlistToolbarState,
                    motion = recommendationNavigation.motion,
                    backdropLayer = backdropLayer,
                    backdropBounds = backdropBounds,
                    modifier = Modifier.statusBarsPadding().align(Alignment.TopCenter),
                )
                UnifiedPlaylistFloatingTools(
                    state = playlistFloatingToolsState,
                    motion = recommendationNavigation.motion,
                    entities = entityNavigation,
                    backdropLayer = backdropLayer,
                    backdropBounds = backdropBounds,
                    bottomInset = barMeasurements.detailInset,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
                EntitySystemBar(
                    entityNavigation,
                    playerMotion,
                    recommendationNavigation,
                    settingsMotion,
                    rootColor = if (immersiveNeteaseSurface) Color.Transparent else Color.Unspecified,
                    rootDarkIcons = neteaseProfileVisual.useDarkForeground.takeIf { immersiveNeteaseSurface },
                )
                PlayerMotionHost(playerMotion, viewModel, playerControlHandoff, playerRetention, homeLaunch.covering) {
                    viewModel.setPlayerExpanded(false)
                }
            }
            UnifiedBackButton(entityNavigation, settingsNavigation, recommendationNavigation, playlistToolbarState,
                qqSearchState, qqSearchViewModel, qqSearchMotion, playerMotion, backdropLayer, backdropBounds)
            HomeLaunchOverlay(
                source = platformState.selectedSource,
                sessionRevision = platformState.sessionRevision,
                sessionStatus = platformState.sessionStatus,
                catalog = catalogState,
                launch = homeLaunch,
                playerRetention = playerRetention,
            )
            QqPlaybackSecurityHost()
            neteaseBackground.dialogContent()
        }
        }
        }
    }
}
