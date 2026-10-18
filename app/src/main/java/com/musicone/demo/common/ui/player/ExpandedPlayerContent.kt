package com.musicone.demo

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
    var lyrics by remember { mutableStateOf(false) }
    val queueState = LocalPlayerQueueState.current
    val controls = rememberPlayerControlsVisibilityState()
    val lyricContent = remember { PlayerLyricsContent() }
    val lyricScope = rememberCoroutineScope()
    val phoneLyricMotion = remember { PhoneLyricsMotion(false, lyricScope) }
    val view = androidx.compose.ui.platform.LocalView.current
    PlayerInteractionPerformance(motion, phoneLyricMotion, track, lyricsRequested = lyrics,
        blocked = !contentActivated || !state.playerExpanded || queueState.visible)
    val visible = LocalPlayerVisible.current
    if (visible) {
        DisposableEffect(view, state.isPlaying, PlaybackOptions.keepScreenOn) {
            val previous = view.keepScreenOn
            view.keepScreenOn = state.isPlaying && PlaybackOptions.keepScreenOn
            onDispose { view.keepScreenOn = previous }
        }
    }
    LaunchedEffect(track.id, track.lyrics.isEmpty(), state.lyricLoadState) {
        if (lyrics && !track.source.supportsLibraryEditing && track.lyrics.isEmpty() && state.lyricLoadState == LyricLoadState.UNAVAILABLE) {
            lyrics = false
            controls.show()
        }
    }
    // 整页退场仍使用当前歌词页眉的锚点，隐藏后再恢复封面页状态。
    LaunchedEffect(motion.phase) {
        if (motion.phase == MotionPhase.HIDDEN) {
            lyrics = false
            phoneLyricMotion.resetWhileHidden()
        }
    }
    CompositionLocalProvider(LocalContentColor provides playerForegroundColor(),
        LocalPlayerLyricsContent provides lyricContent,
        LocalRetainedPhoneLyricsMotion provides phoneLyricMotion) {
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
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
                        if (lyrics || track.source.supportsLibraryEditing || track.lyrics.isNotEmpty()) lyrics = !lyrics
                        if (!lyrics) controls.show()
                    },
                    onQueue = {
                        controls.show()
                        queueState.open()
                    },
                )
            }
            }
        }
    }
}

internal fun Modifier.playerDetailReveal(motion: PageMotion): Modifier = composed {
    val prewarming = LocalPlayerPrewarming.current
    graphicsLayer { alpha = if (prewarming) 1f else contentReveal(motion.value) }
}
