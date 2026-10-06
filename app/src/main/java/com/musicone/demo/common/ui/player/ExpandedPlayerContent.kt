package com.musicone.demo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.composed

@Composable
internal fun ExpandedPlayerContent(state: MusicOneUiState, track: MusicTrack, visualTrack: MusicTrack, motion: PageMotion,
    viewModel: MusicOneViewModel, onDismiss: () -> Unit, contentActivated: Boolean) {
    var lyrics by remember { mutableStateOf(false) }
    var queue by rememberSaveable { mutableStateOf(false) }
    val controls = rememberPlayerControlsVisibilityState()
    val view = androidx.compose.ui.platform.LocalView.current
    val visible = LocalPlayerVisible.current
    if (visible) {
        DisposableEffect(view, state.isPlaying, PlaybackOptions.keepScreenOn) {
            val previous = view.keepScreenOn
            view.keepScreenOn = state.isPlaying && PlaybackOptions.keepScreenOn
            onDispose { view.keepScreenOn = previous }
        }
    }
    LaunchedEffect(visible) { if (!visible) queue = false }
    LaunchedEffect(motion.wantsOpen) { if (!motion.wantsOpen) lyrics = false }
    CompositionLocalProvider(LocalContentColor provides playerForegroundColor()) {
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            val spec = playerLayoutSpec(maxWidth, maxHeight)
            CompositionLocalProvider(LocalPlayerLayoutSpec provides spec) {
            if (spec.mode == PlayerLayoutMode.LANDSCAPE_TWO_PANE) {
                BalancedTabletPlayer(state, track, visualTrack, motion, viewModel, contentActivated) {
                    controls.show()
                    queue = true
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
                    onQueue = {
                        controls.show()
                        queue = true
                    },
                )
            }
            }
        }
        if (queue && visible) PlayerQueueSheet(state, viewModel) { queue = false }
    }
}

internal fun Modifier.playerDetailReveal(motion: PageMotion): Modifier = composed {
    val prewarming = LocalPlayerPrewarming.current
    graphicsLayer { alpha = if (prewarming) 1f else contentReveal(motion.value) }
}
