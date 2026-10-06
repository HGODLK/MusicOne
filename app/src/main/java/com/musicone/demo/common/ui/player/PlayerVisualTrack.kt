package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/** 封面与文字提前显示预览目标，播放进度和歌词仍使用真实起播歌曲。 */
internal fun playerVisualTracks(
    playback: StateFlow<MusicOneUiState>,
    preview: StateFlow<RapidTrackSwitchPresentation?>,
): Flow<MusicTrack> = combine(
    playback.map { it.currentTrack }.distinctUntilChanged(),
    preview.map { it?.track }.distinctUntilChanged(),
) { _, _ ->
    // 撤下预览时直接读取已提交的播放状态，不能接回滞后一帧的组合参数或 Flow 通知。
    val settled = playback.value.currentTrack
    val pending = preview.value?.track
    settled?.let { playerVisualTrack(it, pending) } ?: pending
}.filterNotNull()
    .distinctUntilChangedBy { it.playerVisualKey() }

internal fun playerVisualTrack(playback: MusicTrack, preview: MusicTrack?): MusicTrack = when {
    preview == null -> playback
    preview.source == playback.source && preview.id == playback.id -> playback
    else -> preview
}

internal fun playerVisualActionTrack(playback: MusicTrack, visual: MusicTrack): MusicTrack =
    if (playback.source == visual.source && playback.id == visual.id) playback else visual

@Composable
internal fun rememberPlayerVisualTrack(
    track: MusicTrack,
    rapidSwitch: RapidTrackSwitch,
    playback: StateFlow<MusicOneUiState>,
): State<MusicTrack> {
    val tracks = remember(rapidSwitch, playback) {
        playerVisualTracks(playback, rapidSwitch.presentation)
    }
    return tracks.collectAsStateWithLifecycleFrom(playback) {
        playerVisualTrack(it.currentTrack ?: track, rapidSwitch.presentation.value?.track)
    }
}

internal data class PlayerVisualKey(
    val source: MusicSource,
    val id: String,
    val title: String,
    val artists: String,
    val artwork: ArtworkIdentity,
    val accessBadge: MusicAccessBadge?,
)

internal fun MusicTrack.playerVisualKey() = PlayerVisualKey(
    source, id, playerPrimaryTitle(title), playerPrimaryArtists(artists), artworkIdentity(), accessBadge,
)
