package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal data class BottomPlayerPresentation(
    val page: MusicOnePage,
    val currentTrack: MusicTrack?,
    val isPlaying: Boolean,
    val hasQueue: Boolean,
)

private fun MusicOneUiState.bottomPlayerPresentation() =
    BottomPlayerPresentation(page, currentTrack, isPlaying, queue.isNotEmpty())

internal fun bottomPlayerPresentations(states: Flow<MusicOneUiState>): Flow<BottomPlayerPresentation> =
    states.map { it.bottomPlayerPresentation() }.distinctUntilChanged { previous, current ->
        previous.page == current.page && previous.isPlaying == current.isPlaying &&
            previous.hasQueue == current.hasQueue &&
            previous.currentTrack?.playerVisualKey() == current.currentTrack?.playerVisualKey()
    }

@Composable
internal fun rememberBottomPlayerPresentation(states: StateFlow<MusicOneUiState>): State<BottomPlayerPresentation> =
    remember(states) { bottomPlayerPresentations(states) }.collectAsStateWithLifecycleFrom(states) {
        it.bottomPlayerPresentation()
    }
