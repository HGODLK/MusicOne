package com.musicone.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
internal fun PhoneExpandedPlayer(
    state: MusicOneUiState,
    track: MusicTrack,
    visualTrack: MusicTrack,
    motion: PageMotion,
    lyrics: Boolean,
    viewModel: MusicOneViewModel,
    controls: PlayerControlsVisibilityState,
    contentActivated: Boolean,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
) {
    val density = LocalDensity.current
    val lyricsState = remember { androidx.compose.runtime.mutableStateOf<PhoneLyricsMotion?>(null) }
    val spec = LocalPlayerLayoutSpec.current
    val canHideControls = spec.mode == PlayerLayoutMode.COMPACT_SINGLE
    val hiddenProgress = if (canHideControls) controls.progress else 0f
    var controlsHeightPx by remember(density) {
        mutableIntStateOf(with(density) { PHONE_CONTROLS_ESTIMATED_HEIGHT.roundToPx() })
    }
    val controlsInset = with(density) { controlsHeightPx.toDp() } * (1f - hiddenProgress)
    LaunchedEffect(lyrics, canHideControls) {
        if (!lyrics || !canHideControls) controls.show()
    }
    Box(Modifier.fillMaxSize().playerControlsHintTouches(controls)) {
        PhonePlayerStage(
            state = state,
            track = track,
            visualTrack = visualTrack,
            motion = motion,
            lyrics = lyrics,
            controlsBottomInset = controlsInset,
            controlsHeightPx = { controlsHeightPx.toFloat() },
            controlsHiddenProgress = { if (canHideControls) controls.progress else 0f },
            viewModel = viewModel,
            contentActivated = contentActivated,
            onLyricsDismiss = onLyrics,
            onLyricsMotion = { lyricsState.value = it },
            contentHorizontalPadding = spec.horizontalPadding,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = PHONE_PLAYER_TOP_PADDING),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = spec.horizontalPadding)
                .onSizeChanged { controlsHeightPx = it.height }
                .playerControlsDrag(controls, enabled = lyrics && canHideControls, reserveBottomButtons = true)
                .graphicsLayer {
                    translationY = if (canHideControls) playerControlsTranslation(controls.progress, size.height) else 0f
                },
        ) {
            ImmersivePlaybackControls(
                state = state,
                track = track,
                motion = motion,
                viewModel = viewModel,
                tablet = false,
                lyrics = lyrics,
                onLyrics = onLyrics,
                onQueue = onQueue,
                lyricsProgress = { lyricsState.value?.lyricsProgress ?: if (lyrics) 1f else 0f },
            )
        }
        if (canHideControls) PlayerControlsRestoreHandle(
            state = controls,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private val PHONE_CONTROLS_ESTIMATED_HEIGHT = 208.dp
internal val PHONE_PLAYER_TOP_PADDING = 44.dp
