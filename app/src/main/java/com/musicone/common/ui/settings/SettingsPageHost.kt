package com.musicone

import androidx.compose.animation.*
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp

/** 设置独立持有展开进度，根页面始终保留，返回时不重建列表。 */
@Composable
internal fun SettingsPageHost(navigation: SettingsNavigation, state: PlatformSettingsUiState,
    model: PlatformSettingsViewModel, motion: PageMotion, bottomInset: androidx.compose.ui.unit.Dp,
    cachePlaylists: List<MusicPlaylist> = emptyList(), onPlayCached: (MusicTrack) -> Unit = {},
    currentTrackId: String? = null, playing: Boolean = false,
    onSelectSource: (MusicSource) -> Unit = model::selectSource) {
    val settingsPages = rememberSaveableStateHolder()
    val supplementalModels = rememberSupplementalSourceModels()
    val sourceController = com.musicone.sourceintegration.SourcePackageController.get(androidx.compose.ui.platform.LocalContext.current)
    var retained by remember { mutableStateOf(SettingsPageTarget(SettingsDestination.SETTINGS)) }
    var origin by remember { mutableStateOf(Rect.Zero) }
    var loginBack by remember { mutableStateOf<(() -> Unit)?>(null) }
    var supplementalBack by remember { mutableStateOf<(() -> Unit)?>(null) }
    // 在组合阶段读取局部返回状态，表单退回后必须重新登记，避免旧处理器一直吞掉返回。
    val backInterceptor = if (navigation.destination == SettingsDestination.SUPPLEMENTAL) supplementalBack
        else if (navigation.destination != SettingsDestination.PLATFORM_LOGIN) null
        else loginBack
    SideEffect { navigation.backInterceptor = backInterceptor }
    DisposableEffect(navigation) { onDispose { navigation.backInterceptor = null } }
    val entryRevision = remember(navigation.destination) { state.sessionRevision }
    LaunchedEffect(state.sessionRevision, navigation.destination) {
        if (navigation.destination == SettingsDestination.PLATFORM_LOGIN && state.sessionRevision > entryRevision &&
            state.sessionStatus == SessionStatus.CONNECTED && !state.working) navigation.backPage()
    }
    SideEffect { motion.waitForTarget = false }
    val target = navigation.pageTarget()
    LaunchedEffect(target) {
        if (navigation.destination != SettingsDestination.CLOSED) {
            retained = target
            if (!motion.mounted) {
                origin = navigation.origin
            }
        }
        motion.request(navigation.destination != SettingsDestination.CLOSED)
    }
    ApplyCacheSettingsOnExit(navigation.destination, motion.mounted) { MusicCacheManager.available()?.applyPendingSettings() }
    if (!motion.mounted) return
    SettingsPlatformTheme(state.selectedSource, state.sessionStatus != SessionStatus.CONNECTED) {
    // 齿轮仅由背景录制层之外的页头绘制，展开表面不能再画一份被齿轮自身采样。
    ExpandingPageSurface(
        origin = origin,
        progress = { motion.value },
        surfaceReveal = ::playerSurfaceReveal,
        contentReveal = ::contentReveal,
        sourceCorner = 24.dp,
    ) {
        AnimatedContent(retained, Modifier.fillMaxSize(), contentKey = { it.stateKey }, transitionSpec = {
            settingsPageTransform(targetState.forward)
        }, label = "设置层级文字衔接") { page ->
            settingsPages.SaveableStateProvider(page.stateKey) {
                val displayed = page.destination
                    when (displayed) {
                        SettingsDestination.SETTINGS -> SettingsHomeScreen(state,
                            navigation::back, navigation::openSources, bottomInset, navigation::openCachedMusic,
                            navigation::openLibrary, navigation::openSupplemental)
                        SettingsDestination.MUSIC_LIBRARY -> MusicLibrarySettingsScreen(navigation::back, bottomInset)
                        SettingsDestination.SUPPLEMENTAL -> Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                            SupplementalSourcesScreen(bottomInset, navigation::backPage, { supplementalBack = it },
                                navigation::openSourceManagement, page.supplementalSourceId, supplementalModels, page.supplementalEntryRevision)
                        }
                        SettingsDestination.SOURCE_MANAGEMENT -> com.musicone.ui.sources.SourceManagementScreen(
                            sourceController, navigation::backPage,
                            onOpenSource = { id ->
                                if (sourceController.running(id)?.entry?.descriptor?.kind == com.musicone.source.sdk.SourceKind.SUPPLEMENTAL)
                                    navigation.openSupplementalSource(id)
                                else {
                                    onSelectSource(MusicSource.fromId(id))
                                    navigation.backPage()
                                }
                            },
                            bottomInset = bottomInset,
                        )
                        SettingsDestination.CACHED_MUSIC, SettingsDestination.CACHED_PLAYLIST -> {
                            CachedMusicScreen(state.selectedSource, cachePlaylists, page.cachedPlaylistId,
                                navigation::back, navigation::openCachedPlaylist, onPlayCached, bottomInset, currentTrackId, playing)
                        }
                        SettingsDestination.SOURCES -> SourceSettingsScreen(
                            state = state,
                            onBack = navigation::back,
                            onSelectSource = onSelectSource,
                            onOpenLogin = {
                                model.startLogin(state.selectedSource)
                            },
                            onLogout = model::logout,
                            onOpenManagement = navigation::openSourceManagement,
                        )
                        SettingsDestination.PLATFORM_LOGIN -> {
                            LaunchedEffect(state.loginSource) { model.startLogin(state.loginSource); navigation.backPage() }
                        }
                        SettingsDestination.CLOSED -> Unit
                    }
            }
        }
    }
    }
}
