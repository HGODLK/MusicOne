package com.musicone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
fun MusicOneApp(viewModel: MusicPlaybackRuntime = viewModel<MusicOneViewModel>().playback) {
    val sourceHost = com.musicone.sourceintegration.SourceApplicationHost.get(androidx.compose.ui.platform.LocalContext.current)
    androidx.compose.runtime.SideEffect { sourceHost.bind(viewModel) }
    CurrentTrackThemeHost(viewModel) {
        PlaybackFailureNoticeHost { MusicLibraryHost(viewModel) { MusicOneContent(viewModel) } }
    }
}

@Composable
private fun MusicOneContent(viewModel: MusicPlaybackRuntime) {
    PlaybackPresentationLifecycle(viewModel)
    val aiContext = androidx.compose.ui.platform.LocalContext.current
    val aiAssistant = remember(aiContext) { AiAssistantController.get(aiContext) }
    androidx.compose.runtime.SideEffect { aiAssistant.bindPlayback(viewModel) }
    val pageBackground = androidx.compose.material3.MaterialTheme.colorScheme.background
    ExperiencePreferences.initialize(androidx.compose.ui.platform.LocalContext.current)
    val navigation by rememberAppPlaybackNavigation(viewModel.state)
    val platformViewModel: PlatformSettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val catalogViewModel: MusicCatalogViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val mainSearchViewModel: MainSourceSearchViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val mainSearchState by mainSearchViewModel.state.collectAsStateWithLifecycle()
    val backdropLayer = rememberGraphicsLayer()
    val entitySceneLayer = rememberGraphicsLayer()
    val miniBackdropUpdates = rememberMiniPlayerBackdropUpdates()
    val mainSearchMotion = rememberMainSourceSearchMotion(mainSearchState, backdropLayer)
    val mainSearchMounted by rememberMainSourceSearchMounted(mainSearchMotion)
    val favoriteViewModel: MusicFavoriteViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val mainLibraryViewModel: MainSourceLibraryViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val platformState by platformViewModel.state.collectAsStateWithLifecycle()
    val sourcePages = com.musicone.sourceintegration.rememberSourceRootPages(platformState.selectedSource, navigation.page)
    val publicPages = com.musicone.sourceintegration.SourceApplicationHost.get(
        androidx.compose.ui.platform.LocalContext.current).contentUi.pages
    com.musicone.sourceintegration.SourceAccountLifecycle(platformState.selectedSource, platformViewModel::refreshSessionIfDue)
    val musicLibraryUi = LocalMusicLibraryUi.current
    androidx.compose.runtime.SideEffect { musicLibraryUi.localMode = platformState.selectedSource == MusicSource.LOCAL }
    val catalogState by catalogViewModel.state.collectAsStateWithLifecycle()
    val favoriteState by favoriteViewModel.state.collectAsStateWithLifecycle()
    val mainLibraryState by mainLibraryViewModel.state.collectAsStateWithLifecycle()
    val sourceBackdrop = rememberSourceBackdropUiController(platformState.selectedSource)
    val sourceVisual = sourceBackdrop.visual
    val globalSourceBackground = sourceBackdrop.hasCustomGlobal
    val immersiveSourceSurface = sourceVisual != null &&
        (globalSourceBackground ||
            navigation.page == MusicOnePage.MY && platformState.sessionStatus == SessionStatus.CONNECTED)
    val playSortedPlaylist = rememberSortedPlaylistPlay(platformState.account?.userId, viewModel::playPlaylist)
    val recommendationNavigation = rememberRecommendationNavigation()
    com.musicone.sourceintegration.SourceCollectionUiHost(recommendationNavigation)
    val settingsNavigation = rememberSettingsNavigation()
    val entityNavigation = rememberEntityNavigation()
    val pageInteraction = remember { PageInteractionActivity() }
    val settingsMotion = rememberPageMotion(false, enterAnimation = PlayerEnterAnimation, exitAnimation = PlayerReturnAnimation)
    LaunchedEffect(platformState.selectedSource, platformState.account?.userId) { entityNavigation.clear() }
    val barMeasurements = remember { BottomBarMeasurements() }
    val playerMotion = rememberPageMotion(navigation.playerExpanded,
        enterAnimation = PlayerEnterAnimation, exitAnimation = PlayerReturnAnimation,
        dragAnimation = PlayerGestureSettlementAnimation, immediateGestureHandoff = true,
        sharedAnchorKeys = PlayerSharedAnchorKeys)
    val refreshPlayerBackdrop = rememberPlayerBackdropRefresh(playerMotion)
    val state by rememberPagePlaybackState(viewModel.state, playerMotion)
    val playerControlHandoff = rememberPlayerControlHandoff()
    val homeLaunch = rememberHomeLaunchState()
    val playerRetention = rememberPlayerRetentionState()
    val mainSearchHomeGuard = rememberMainSourceSearchHomeGuard(navigation.page, mainSearchViewModel, mainSearchMotion)
    val publicRootSearch = remember { com.musicone.sourceintegration.SourcePublicRootSearchBridge() }
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
    LaunchedEffect(platformState.selectedSource, platformState.sessionRevision, platformState.sessionStatus, sourcePages.initializing) {
        if (sourcePages.initializing || platformState.sessionStatus == SessionStatus.CHECKING) return@LaunchedEffect
        catalogViewModel.configure(
            if (sourcePages.isCustom) MusicSource.LOCAL else platformState.selectedSource,
            platformState.homeSessionRevision,
            platformState.sessionStatus == SessionStatus.CONNECTED,
        )
        favoriteViewModel.configure(platformState.sessionRevision)
        mainLibraryViewModel.configure(platformState.selectedSource,
            platformState.sessionRevision)
    }
    LaunchedEffect(platformState.sessionRevision) {
        viewModel.refreshQualityOptions()
        if (platformState.account != null && settingsNavigation.destination == SettingsDestination.PLATFORM_LOGIN) {
            settingsNavigation.back()
        }
    }
    LaunchedEffect(favoriteState.changes) {
        mainLibraryViewModel.syncFavoriteChanges(favoriteState.changes)
    }
    val publicSceneBridge = remember(settingsNavigation, settingsMotion, playerMotion, barMeasurements) {
        com.musicone.sourceintegration.SourcePublicSceneBridge(settingsNavigation, settingsMotion, playerMotion, barMeasurements)
    }
    publicSceneBridge.selectedRoot = sourcePages.rootIdentity(navigation.page)
    val publicScene = publicSceneBridge.active
    val activeSearchGuard = publicScene?.searchHomeGuard ?: mainSearchHomeGuard
    val rootNavigation = rememberRootPageNavigation(navigation.page, viewModel::setPage,
        activeSearchGuard::beforePageChange, activeSearchGuard::onBottomBarContact, activeSearchGuard::blocksBottomBarDrag)
    val publicSceneDetail = publicScene?.detailMounted == true
    val rootAvailable = !recommendationNavigation.motion.mounted && !playerMotion.mounted &&
        !settingsMotion.mounted && entityNavigation.pages.isEmpty() && !publicPages.mounted
    val rootVisible = rootAvailable && !publicSceneDetail
    com.musicone.sourceintegration.SourcePublicRootSearchHost(publicRootSearch, mainSearchViewModel, mainSearchState,
        mainSearchMotion, platformState.selectedSource, navigation.page, platformState.sessionRevision,
        platformState.account?.userId, sourcePages.isCustom,
        !sourcePages.initializing && platformState.sessionStatus != SessionStatus.CHECKING,
        rootAvailable && publicScene == null)
    val rootSearchEnabled = (!sourcePages.isCustom || publicRootSearch.mounted) && sourcePages.supportsSearch
    val overlaySceneActive = entityNavigation.pages.isNotEmpty() || publicPages.mounted || publicScene?.overlayBackdrop == true || settingsMotion.mounted
    val myVisible = navigation.page == MusicOnePage.MY && rootVisible
    MusicLibrarySynchronization(viewModel, favoriteViewModel, platformState.selectedSource,
        platformState.sessionRevision, myVisible, {
            if (!sourcePages.isCustom) mainLibraryViewModel.refresh()
        }, recommendationNavigation.cards,
        rootVisible)

    val queueState = rememberPlayerQueueState()
    val phonePresentation = rememberPhonePlayerPresentation(navigation.playerExpanded, playerMotion, queueState)
    val sourcePlayer = com.musicone.sourceintegration.rememberSourcePlayerMotion(viewModel, playerMotion, phonePresentation)
    PhonePlayerSystemBars(phonePresentation.rotationDegrees != 0 && !phonePresentation.relatedPages && playerMotion.mounted)
    PlaybackNoticePlacementEffect(playerMotion.mounted, phonePresentation.rotationDegrees, rootVisible, barMeasurements)

    com.musicone.sourceintegration.SourcePublicPageSessionHost {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalPhonePlayerRotation provides phonePresentation.rotationDegrees,
        LocalPhonePlayerPresentation provides phonePresentation,
        com.musicone.sourceintegration.LocalSourcePublicSceneBridge provides publicSceneBridge,
        com.musicone.sourceintegration.LocalSourcePublicRootSearchBridge provides publicRootSearch,
        LocalMyPageVisible provides myVisible,
        LocalPlayerQueueState provides queueState,
        LocalPlaybackQueueActions provides rememberPlaybackQueueActions(viewModel),
        LocalPlaylistMotion provides recommendationNavigation.motion,
        LocalPlaylistCardTransition provides recommendationNavigation.cards,
        LocalPlayerMotion provides playerMotion,
        LocalMainSourceSearchMotion provides mainSearchMotion,
        LocalEntityNavigation provides entityNavigation,
        LocalUnifiedBack provides true,
        LocalPageInteraction provides pageInteraction,
        LocalEntityActive provides entityNavigation.pages.isEmpty(),
        LocalRecommendationVisible provides (settingsNavigation.destination == SettingsDestination.CLOSED &&
            !mainSearchMounted && !recommendationNavigation.motion.mounted && entityNavigation.pages.isEmpty()),
        LocalPlayerSurfaceTexture provides playerSurfaceTexture,
        LocalPlayerAtmosphereMotion provides playerAtmosphereMotion,
        LocalPlayerBackdropTexture provides backdropLayer,
        LocalMiniPlayerBackdropUpdates provides miniBackdropUpdates,
        LocalPlayerControlHandoff provides playerControlHandoff,
        LocalPlayerBackdropBounds provides backdropBounds,
        LocalPrimaryPageHeaderState provides primaryHeaderState,
        LocalSourceBackdropVisual provides sourceVisual.takeIf { globalSourceBackground },
        LocalMusicFavorites provides libraryFavoriteActions(MusicFavoriteActions(
            favoriteState,
            favoriteViewModel::toggle,
            favoriteViewModel::toggleDeferredRemoval,
            favoriteViewModel::flushDeferredRemovals,
            currentState = { favoriteViewModel.state.value },
        )),
    ) {
        PlatformMusicTheme(
            platformState.selectedSource,
            neutral = platformState.sessionStatus != SessionStatus.CONNECTED,
        ) {
        AccountEntryHost(platformState, settingsNavigation, platformViewModel::selectSource,
            platformViewModel::startLogin, platformViewModel::selectAfterLogin) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .observePageInteraction(pageInteraction)
                .background(pageBackground),
        ) {
            // 声明背景的音源始终预挂载背景；首页的不透明底色会遮住它，
            // 翻到“我的”时由透明页面自然揭示，不在动画结束时突然插入。
            if (sourceVisual != null) {
                SourceBackdrop(
                    visual = sourceVisual,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            MiniPlayerBackdropScene(entitySceneLayer, overlaySceneActive,
                { entitySceneBounds = it }) {
            Box(
                Modifier
                    .fillMaxSize()
                    .playerStableStatusBarsPadding()
                    .onGloballyPositioned { backdropBounds = it.boundsInRoot() }
                    .drawWithContent {
                        if (refreshPlayerBackdrop.value) {
                            backdropLayer.record {
                                drawRect(if (immersiveSourceSurface) Color.Transparent else pageBackground)
                                this@drawWithContent.drawContent()
                            }
                            drawLayer(backdropLayer)
                        } else {
                            drawContent()
                        }
                    }.graphicsLayer(),
            ) {
                run {
                    SourceBackdropForeground(sourceVisual.takeIf { globalSourceBackground }) {
                    Column(Modifier.fillMaxSize()) {
                        if (platformState.selectedSource == MusicSource.LOCAL) {
                            LocalMusicScreen(state, barMeasurements.detailInset, recommendationNavigation::open, playSortedPlaylist) {
                                settingsNavigation.origin = it
                                settingsNavigation.openLibrary()
                            }
                        } else HorizontalPager(
                            state = rootNavigation.pagerState,
                            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                            beyondViewportPageCount = 1,
                            userScrollEnabled = false,
                            key = { MusicOnePage.entries[it] },
                        ) { page ->
                            // 每页单独裁剪并铺满底色，避免相邻页内容在边缘抗锯齿处泄漏一列像素。
                            val immersivePage = sourceVisual != null &&
                                (globalSourceBackground || MusicOnePage.entries[page] == MusicOnePage.MY &&
                                    platformState.sessionStatus == SessionStatus.CONNECTED)
                            Box(
                                Modifier.fillMaxSize().clipToBounds()
                                    .background(if (immersivePage) Color.Transparent else pageBackground),
                            ) {
                                if (sourcePages.preparing) sourcePages.Preparation(platformState.selectedSource,
                                    MusicOnePage.entries[page], barMeasurements.homeInset,
                                    onChooseSource = { settingsNavigation.choosingSource = true })
                                else if (sourcePages.isCustom) sourcePages.Content(MusicOnePage.entries[page],
                                    page == (if (sourcePages.hostRootTransition) navigation.page.ordinal
                                        else rootNavigation.pagerState.currentPage), barMeasurements.homeInset,
                                    onExit = { settingsNavigation.choosingSource = true }, interactive = rootAvailable)
                                else when (MusicOnePage.entries[page]) {
                                    MusicOnePage.HOME -> PlatformHomeScreen(
                                        source = platformState.selectedSource,
                                        state = state,
                                        catalog = catalogState,
                                        viewModel = viewModel,
                                        sessionRevision = platformState.homeSessionRevision,
                                        sessionStatus = platformState.sessionStatus,
                                        bottomInset = barMeasurements.homeInset,
                                        onChooseSource = { settingsNavigation.choosingSource = true },
                                        onOpenRecommendation = { playlist ->
                                            recommendationNavigation.open(playlist)
                                        },
                                        onOpenLoadedPlaylist = recommendationNavigation::open,
                                        onPlayRecommendation = { playlist ->
                                            catalogViewModel.loadPlaylist(playlist, playSortedPlaylist)
                                        },
                                        onRefreshRecommendations = catalogViewModel::refreshRecommendations,
                                        onRefreshRecommendedTracks = catalogViewModel::refreshRecommendedTracks,
                                    )
                                    MusicOnePage.MY -> PlatformMyScreen(
                                        platformState.account,
                                        mainLibraryState,
                                        barMeasurements.homeInset,
                                        settingsNavigation::openSettings,
                                        { playlist ->
                                            recommendationNavigation.open(playlist)
                                        },
                                        mainLibraryViewModel::refresh,
                                        { playlist ->
                                            if (playlist.isManagedLibrary) playSortedPlaylist(playlist)
                                            else catalogViewModel.loadPlaylist(playlist, playSortedPlaylist)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    }
                    if (rootSearchEnabled) MainSourceSearchResults(
                        mainSearchState, mainSearchViewModel, mainSearchMotion, state, barMeasurements.detailInset,
                        viewModel::playTrackNext,
                        { playlist ->
                            recommendationNavigation.open(playlist)
                        },
                        recommendationNavigation.motion.mounted || playerMotion.mounted,
                    )
                    RecommendationDestination(
                        recommendationNavigation, state, viewModel, barMeasurements.detailInset,
                        catalogState.loadingPlaylistId, catalogState.actionMessage,
                        mainLibraryState,
                        platformState.account?.userId,
                        mainLibraryViewModel::toggleCollected,
                        { playlist, contentChanged ->
                            if (contentChanged) catalogViewModel.invalidatePlaylist(playlist.id)
                            mainLibraryViewModel.refreshAfterPlaylistReturn(playlist.id, contentChanged)
                        },
                        playlistToolbarState,
                        playlistFloatingToolsState,
                        sourceBackdrop.playlistRevision,
                        { playlist -> sourceBackdrop.choosePlaylist(playlist.id) },
                        { playlist -> sourceBackdrop.restorePlaylist(playlist.id) },
                    )

                }
            }

            if (settingsNavigation.destination == SettingsDestination.CLOSED) {
                // 详情页玻璃仍采样根背景；详情层只进入迷你播放器的独立合成录制层。
                Box(Modifier.fillMaxSize().playerStableStatusBarsPadding()) {
                    EntityPageHost(entityNavigation, state, viewModel, barMeasurements.detailInset,
                        mainLibraryState.createdPlaylists, { mainLibraryViewModel.refresh() })
                }
            }
            if (publicPages.mounted) Box(Modifier.fillMaxSize().playerStableStatusBarsPadding()) {
                com.musicone.sourceintegration.SourcePublicPageHost(publicPages, viewModel,
                    barMeasurements.detailInset.value, interactive = !playerMotion.mounted && !settingsMotion.mounted)
            }
            // 设置表面的状态栏避让移到各页自身：AI 聊天顶栏需要整条铺到屏幕最顶，各页仍按原样避让。
            Box(Modifier.fillMaxSize()) {
                SettingsPageHost(settingsNavigation, platformState, platformViewModel, settingsMotion, barMeasurements.detailInset,
                    listOfNotNull(mainLibraryState.favoritePlaylist) + mainLibraryState.createdPlaylists +
                        mainLibraryState.collectedPlaylists,
                    viewModel::playTrackNext, state.currentTrack?.id, state.isPlaying,
                    aiController = aiAssistant)
            }
            }

            run {
                val publicRootHeader = publicScene?.rootHeader
                // AI 有自己的顶栏，进入时卸下根页透明命中层，离开后沿原动效恢复。
                if (settingsNavigation.destination != SettingsDestination.AI_ASSISTANT)
                    com.musicone.sourceintegration.SourcePublicSceneHeader(publicScene)
                if (com.musicone.sourceintegration.sourcePrimaryHeaderVisible(sourcePages.isCustom, sourcePages.atRoot,
                        publicRootSearch.mounted, sourcePages.hostRootTransition, publicScene) &&
                    !publicPages.mounted && entityNavigation.pages.isEmpty()) {
                    PrimaryPageHeader(
                        page = navigation.page,
                        pagerPosition = { rootNavigation.position },
                        backdropLayer = backdropLayer,
                        backdropBounds = backdropBounds,
                        onSearch = publicRootHeader?.onSearch ?: {
                            if (sourcePages.isCustom) sourcePages.openSearch(navigation.page)
                            else if (platformState.selectedSource.requiresAccount && platformState.sessionStatus != SessionStatus.CONNECTED) {
                                settingsNavigation.choosingSource = true
                            } else {
                                mainSearchViewModel.open()
                            }
                        },
                        onSettings = publicRootHeader?.onSettings ?: settingsNavigation::openSettings,
                        onSettingsBounds = publicRootHeader?.onSettingsBounds ?: { settingsNavigation.origin = it },
                        motionVisibility = {
                            primaryHeaderMotionVisibility(
                                publicScene?.playlists?.motion?.value ?: recommendationNavigation.motion.value,
                                playerMotion.value,
                            )
                        },
                        modifier = Modifier.playerStableStatusBarsPadding().align(Alignment.TopCenter),
                        searchProgress = { publicScene?.search?.menu?.value ?: mainSearchMotion.menu.value },
                        settingsProgress = { settingsMotion.value },
                        settingsMounted = { settingsMotion.mounted },
                        contentColor = sourceVisual?.foreground.takeIf { immersiveSourceSurface },
                        showAction = settingsNavigation.destination != SettingsDestination.AI_ASSISTANT,
                    )
                }
                if (rootSearchEnabled) MainSourceSearchMenu(
                    mainSearchState, mainSearchViewModel, mainSearchMotion, backdropLayer, backdropBounds,
                    recommendationNavigation.motion.mounted || playerMotion.mounted || entityNavigation.pages.isNotEmpty(),
                )
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalPlaylistMotion provides (publicScene?.playlists?.motion ?: recommendationNavigation.motion),
                    LocalEntityNavigation provides (publicScene?.entities ?: entityNavigation),
                    LocalMainSourceSearchMotion provides (publicScene?.search ?: mainSearchMotion)) {
                BottomPlayerBar(
                    viewModel, publicScene?.playlists?.motion ?: recommendationNavigation.motion, playerMotion, barMeasurements,
                    backdropLayer, backdropBounds, playerControlHandoff, rootNavigation, publicScene?.entities ?: entityNavigation,
                    entitySceneLayer, entitySceneBounds,
                    platformState.selectedSource != MusicSource.LOCAL && entityNavigation.pages.isEmpty() && !publicPages.mounted,
                    animatePlayerTransitions = !sourcePages.isCustom || publicPages.mounted,
                    publicPageMounted = overlaySceneActive,
                    immersiveVisual = sourceVisual.takeIf { immersiveSourceSurface },
                    sourcePlayerOpening = sourcePlayer?.opening,
                    searchProgress = { maxOf(mainSearchMotion.full.value, settingsMotion.value,
                        publicScene?.search?.full?.value ?: 0f,
                        publicScene?.entities?.pages?.maxOfOrNull { it.motion.value } ?: 0f) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
                }
                if (publicScene == null && !publicPages.mounted && entityNavigation.pages.isEmpty()) PlaylistToolbarOverlay(
                    state = playlistToolbarState,
                    motion = recommendationNavigation.motion,
                    backdropLayer = backdropLayer,
                    backdropBounds = backdropBounds,
                    modifier = Modifier.playerStableStatusBarsPadding().align(Alignment.TopCenter),
                )
                if (publicScene == null && !publicPages.mounted) UnifiedPlaylistFloatingTools(
                    state = playlistFloatingToolsState,
                    motion = recommendationNavigation.motion,
                    entities = entityNavigation,
                    backdropLayer = backdropLayer,
                    backdropBounds = backdropBounds,
                    bottomInset = barMeasurements.detailInset,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
                EntitySystemBar(
                    publicScene?.entities ?: entityNavigation,
                    playerMotion,
                    publicScene?.playlists ?: recommendationNavigation,
                    settingsMotion,
                    rootColor = if (immersiveSourceSurface) Color.Transparent else Color.Unspecified,
                    rootDarkIcons = sourceVisual?.useDarkForeground.takeIf { immersiveSourceSurface },
                )
                PlayerMotionHost(playerMotion, viewModel, playerControlHandoff, playerRetention, homeLaunch.covering,
                    playerTransitionBackdrop(overlaySceneActive, backdropLayer, backdropBounds,
                        entitySceneLayer, entitySceneBounds), sourcePlayer) {
                    viewModel.setPlayerExpanded(false)
                }
            }
            // 设置页及其退出转场采样现有合成层，按钮本身仍留在录制层之外。
            val settingsBackLayer = entitySceneLayer.takeIf { settingsMotion.mounted }
            if (phonePresentation.rotationDegrees == 0)
                com.musicone.sourceintegration.SourcePublicSceneBack(publicScene, settingsBackLayer, entitySceneBounds)
            if (publicScene == null && !publicPages.mounted && phonePresentation.rotationDegrees == 0) UnifiedBackButton(entityNavigation, settingsNavigation, recommendationNavigation, playlistToolbarState,
                mainSearchState, mainSearchViewModel, mainSearchMotion, playerMotion,
                settingsBackLayer ?: backdropLayer, if (settingsBackLayer != null) entitySceneBounds else backdropBounds)
            sourcePages.Back(!publicRootSearch.mounted && !publicPages.mounted && !publicSceneDetail && settingsNavigation.destination == SettingsDestination.CLOSED && !playerMotion.mounted)
            PlayerQueueHost(queueState, viewModel)
            HomeLaunchOverlay(
                launch = homeLaunch,
            )
            sourceBackdrop.dialogContent()
        }
        }
        }
    }
    }
}
