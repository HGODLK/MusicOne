package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal data class PlayerQueuePresentation(
    val queue: List<MusicTrack>,
    val currentTrackId: String?,
    val isPlaying: Boolean,
)

private fun MusicOneUiState.queuePresentation() =
    PlayerQueuePresentation(queue, currentTrack?.id, isPlaying)

internal fun playerQueuePresentations(states: Flow<MusicOneUiState>): Flow<PlayerQueuePresentation> =
    states.map { it.queuePresentation() }.distinctUntilChanged()

/** 队列独立订阅实时状态，不受完整播放器遮挡底页时的快照冻结影响。 */
@Composable
internal fun rememberPlayerQueuePresentation(states: StateFlow<MusicOneUiState>): State<PlayerQueuePresentation> =
    remember(states) { playerQueuePresentations(states) }.collectAsStateWithLifecycleFrom(states) {
        it.queuePresentation()
    }
