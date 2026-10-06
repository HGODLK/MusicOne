package com.musicone.demo

import androidx.compose.animation.*
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp

/** 设置独立持有展开进度，根页面始终保留，返回时不重建列表。 */
@Composable
internal fun SettingsPageHost(navigation: SettingsNavigation, state: PlatformSettingsUiState,
    model: PlatformSettingsViewModel, motion: PageMotion, bottomInset: androidx.compose.ui.unit.Dp,
    cachePlaylists: List<MusicPlaylist> = emptyList(), onPlayCached: (MusicTrack) -> Unit = {}) {
    var retained by remember { mutableStateOf(SettingsDestination.SETTINGS) }
    var origin by remember { mutableStateOf(Rect.Zero) }
    var loginBack by remember { mutableStateOf<(() -> Unit)?>(null) }
    // 在组合阶段读取局部返回状态，表单退回后必须重新登记，避免旧处理器一直吞掉返回。
    val backInterceptor = if (navigation.destination != SettingsDestination.PLATFORM_LOGIN) null
        else if (state.securityVerification != null) model::cancelSecurityVerification else loginBack
    SideEffect { navigation.backInterceptor = backInterceptor }
    DisposableEffect(navigation) { onDispose { navigation.backInterceptor = null } }
    val entryRevision = remember(navigation.destination) { state.sessionRevision }
    LaunchedEffect(state.sessionRevision, navigation.destination) {
        if (navigation.destination == SettingsDestination.PLATFORM_LOGIN && state.sessionRevision > entryRevision &&
            state.sessionStatus == SessionStatus.CONNECTED && !state.working) navigation.backPage()
    }
    SideEffect { motion.waitForTarget = false }
    LaunchedEffect(navigation.destination) {
        if (navigation.destination != SettingsDestination.CLOSED) {
            retained = navigation.destination
            if (!motion.mounted) {
                origin = navigation.origin
            }
        }
        motion.request(navigation.destination != SettingsDestination.CLOSED)
    }
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
        AnimatedContent(retained, transitionSpec = {
            val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
            (fadeIn(musicMotion(320)) + slideInHorizontally(musicMotion(360)) { direction * it / 16 }) togetherWith
                (fadeOut(musicMotion(220)) + slideOutHorizontally(musicMotion(280)) { -direction * it / 16 })
        }, label = "设置层级文字衔接") { displayed ->
                    when (displayed) {
                        SettingsDestination.SETTINGS -> SettingsHomeScreen(state,
                            navigation::back, navigation::openSources, bottomInset, navigation::openCachedMusic)
                        SettingsDestination.CACHED_MUSIC, SettingsDestination.CACHED_PLAYLIST -> CachedMusicScreen(
                            state.selectedSource, cachePlaylists,
                            if (displayed == SettingsDestination.CACHED_PLAYLIST) navigation.cachedPlaylistId else null,
                            navigation::back, navigation::openCachedPlaylist, onPlayCached, bottomInset)
                        SettingsDestination.SOURCES -> SourceSettingsScreen(
                            state = state,
                            onBack = navigation::back,
                            onSelectSource = model::selectSource,
                            onOpenLogin = {
                                model.startLogin(state.selectedSource)
                                navigation.openPlatformLogin()
                            },
                            onLogout = model::logout,
                        )
                        SettingsDestination.PLATFORM_LOGIN -> PlatformLoginScreen(
                            state = state,
                            onBack = navigation::back,
                            onPhoneChange = model::setPhone,
                            onCountryCodeChange = model::setCountryCode,
                            onCaptchaChange = model::setCaptcha,
                            onSendCaptcha = model::sendCaptcha,
                            onLogin = model::login,
                            onSelectLoginMethod = model::selectLoginMethod,
                            onStartQrLogin = model::startQrLogin,
                            onStopQrLogin = model::stopQrLogin,
                            onCancelSecurityVerification = model::cancelSecurityVerification,
                            onCompleteSecurityVerification = model::completeSecurityVerification,
                            onLocalBackChange = { loginBack = it },
                        )
                        SettingsDestination.CLOSED -> Unit
                    }

        }
    }
    }
}
