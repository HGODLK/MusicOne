package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.composed

@Composable
internal fun ExpandedPlayerContent(state: MusicOneUiState, track: MusicTrack, visualTrack: MusicTrack, motion: PageMotion,
    viewModel: MusicPlaybackRuntime, onDismiss: () -> Unit, contentActivated: Boolean) {
    val localLyrics = remember { mutableStateOf(false) }
    val localLandscapeLyrics = remember { mutableStateOf(true) }
    val queueState = LocalPlayerQueueState.current
    val phone = isPhonePlayerConfiguration(androidx.compose.ui.platform.LocalConfiguration.current)
    val phoneRotation = LocalPhonePlayerRotation.current
    val phonePresentation = LocalPhonePlayerPresentation.current
    LaunchedEffect(visualTrack.source, visualTrack.id) {
        if (phone) phonePresentation?.contentChanged()
    }
    var lyrics by (phonePresentation?.portraitLyrics?.takeIf { phone } ?: localLyrics)
    var landscapeLyrics by (phonePresentation?.landscapeLyrics?.takeIf { phone } ?: localLandscapeLyrics)
    val controls = rememberPlayerControlsVisibilityState()
    if (phone) BindPhonePlayerControlsTurn(phonePresentation, controls)
    val lyricContent = remember { PlayerLyricsContent() }
    val lyricScope = rememberCoroutineScope()
    val lyricFrames = LocalLyricFrameDriver.current
    val phoneLyricMotion = remember(lyricFrames, phonePresentation) {
        PhoneLyricsMotion(lyrics, lyricScope, lyricFrames) {
            phonePresentation?.turn?.followLayoutMotion(PhonePlayerPresentation.BodyKeys)
        }
    }
    DisposableEffect(phonePresentation, phoneLyricMotion, phone) {
        val owner = Any()
        if (phone) phonePresentation?.lyricsRotationGate?.attach(owner, phoneLyricMotion)
        onDispose { phonePresentation?.lyricsRotationGate?.detach(owner) }
    }
    if (phone) PhonePlayerDiagnosticHost(motion, phoneLyricMotion, phonePresentation, phoneRotation, lyrics)
    PlayerInteractionPerformance(motion, phoneLyricMotion, track, lyricsRequested = lyrics,
        blocked = !contentActivated || !state.playerExpanded || queueState.visible)
    val visible = LocalPlayerVisible.current
    // 保留退场中的歌词与页眉锚点，完全隐藏后再恢复原来的关闭端点。
    LaunchedEffect(motion.phase) {
        if (motion.phase == MotionPhase.HIDDEN) {
            lyrics = false
            phoneLyricMotion.resetWhileHidden()
        }
    }
    CompositionLocalProvider(LocalContentColor provides playerForegroundColor(),
        LocalPlayerLyricsContent provides lyricContent,
        LocalRetainedPhoneLyricsMotion provides phoneLyricMotion,
        LocalPhonePlayerTurn provides phonePresentation?.turn?.takeIf { phone },
        LocalPhonePlayerFixedActions provides phone) {
        Box(Modifier.fillMaxSize()) {
        PhoneLyricsColumnHost(phone) {
        PhonePlayerWindowHost(phoneRotation) {
        PhoneLyricsSurfaceHost(phone, motion) {
            Box(Modifier.fillMaxSize()) {
                if (phoneRotation != 0) {
                    Box(Modifier.fillMaxSize().padding(phonePlayerLandscapePadding(phoneRotation, balanceHorizontal = true))) {
                        PhoneLandscapePlayer(state, track, visualTrack, motion, viewModel, contentActivated,
                            showLyrics = landscapeLyrics, lyricsEndInset = phoneLandscapeLyricsEndInset(phoneRotation),
                            onCloseLyrics = { landscapeLyrics = false })
                    }
                } else {
                    BoxWithConstraints(Modifier.fillMaxSize().playerStableStatusBarsPadding().navigationBarsPadding()) {
                        val spec = playerLayoutSpec(maxWidth, maxHeight)
                        CompositionLocalProvider(LocalPlayerLayoutSpec provides spec) {
                            if (spec.mode == PlayerLayoutMode.LANDSCAPE_TWO_PANE) {
                                BalancedTabletPlayer(state, track, visualTrack, motion, viewModel, contentActivated) {
                                    controls.show()
                                    queueState.open()
                                }
                            } else {
                                PhoneExpandedPlayer(
                                    state = state,
                                    track = track,
                                    visualTrack = visualTrack,
                                    motion = motion,
                                    lyrics = lyrics,
                                    viewModel = viewModel,
                                    controls = controls,
                                    contentActivated = contentActivated,
                                    onLyrics = {
                                        lyrics = !lyrics
                                        if (!lyrics) controls.show()
                                    },
                                    onQueue = { controls.show(); queueState.open() },
                                )
                            }
                        }
                    }
                }
            }
            if (phone) Box(Modifier.fillMaxSize().playerDetailReveal(motion)) {
                val landscape = phoneRotation != 0
                val retryLyrics = state.lyricLoadState == LyricLoadState.RETRYABLE && track.lyrics.isEmpty()
                PhonePlayerFixedActions(phoneRotation, controls = controls,
                    enabled = visible && contentActivated && state.playerExpanded,
                    showLyrics = if (landscape) landscapeLyrics else lyrics,
                    onLyrics = {
                        // 没有歌词也照常打开，由歌词页显示“暂无歌词”；加载失败时打开同时重试一次。
                        if (retryLyrics && !(if (landscape) landscapeLyrics else lyrics)) viewModel.retryLyrics()
                        if (landscape) landscapeLyrics = !landscapeLyrics
                        else {
                            lyrics = !lyrics
                            if (!lyrics) controls.show()
                        }
                    },
                    onQueue = { controls.show(); queueState.open() },
                    lyricsProgress = { phoneLyricMotion.lyricsProgress },
                )
            }
            if (phoneRotation != 0) PhonePlayerWindowHost(-phoneRotation) {
                PhoneLandscapePlaybackActions(state, track, motion, viewModel, phoneRotation)
            }
        }
        }
        }
            if (phone) PhonePlayerClockTransition(visualTrack, motion, landscapeLyrics,
                contentActivated, phoneRotation)
        }
    }
}

internal fun Modifier.playerDetailReveal(motion: PageMotion): Modifier = composed {
    val prewarming = LocalPlayerPrewarming.current
    graphicsLayer { alpha = if (prewarming) 1f else contentReveal(motion.value) }
}
