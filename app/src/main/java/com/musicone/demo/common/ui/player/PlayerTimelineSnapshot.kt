package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal data class PlayerTimelineSnapshot(
    val trackId: String,
    val durationMs: Long,
    val positionMs: Long,
    val advancing: Boolean,
    val switching: Boolean,
)

internal fun playerTimelineSnapshot(
    playback: MusicOneUiState,
    progress: PlaybackProgressSnapshot,
    activity: PlaybackActivity,
    preview: RapidTrackSwitchPresentation?,
): PlayerTimelineSnapshot {
    val track = playback.currentTrack
    return PlayerTimelineSnapshot(
        track?.id.orEmpty(), track?.durationMs ?: 0L,
        progress.positionMs.takeIf { progress.trackId == track?.id } ?: 0L,
        activity.advancing && activity.trackId == track?.id,
        preview != null,
    )
}

internal fun playerTimelineSnapshots(
    playback: StateFlow<MusicOneUiState>,
    progress: StateFlow<PlaybackProgressSnapshot>,
    activity: StateFlow<PlaybackActivity>,
    preview: StateFlow<RapidTrackSwitchPresentation?>,
) = combine(
    playback.map { it.currentTrack?.let { track -> track.id to track.durationMs } }.distinctUntilChanged(),
    progress, activity,
    preview.map { it?.token }.distinctUntilChanged(),
) { _, _, _, _ ->
    // 复用封面交接的同步读取方式，预览撤下时不接回尚未送达的旧歌曲通知。
    playerTimelineSnapshot(playback.value, progress.value, activity.value, preview.value)
}.distinctUntilChanged()

@Composable
internal fun rememberPlayerTimelineSnapshot(viewModel: MusicOneViewModel): State<PlayerTimelineSnapshot> =
    remember(viewModel) {
        playerTimelineSnapshots(viewModel.state, viewModel.playbackProgress,
            viewModel.playbackActivity, viewModel.rapidTrackSwitch.presentation)
    }.collectAsStateWithLifecycleFrom(viewModel.state) { playback ->
        playerTimelineSnapshot(playback, viewModel.playbackProgress.value,
            viewModel.playbackActivity.value, viewModel.rapidTrackSwitch.presentation.value)
    }
