package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

/** 歌词只依赖显示和定位数据，播放地址、音质及其他元数据不参与窗口交接。 */
internal data class LyricPresentation(
    val id: String,
    val source: MusicSource,
    val durationMs: Long,
    val lyrics: List<TimedLyric>,
)

internal data class LyricPresentationRequest(
    val trackId: String,
    val presentation: LyricPresentation?,
    val direction: TrackTransitionDirection,
)

internal fun lyricPresentationRequest(
    track: MusicTrack,
    loadState: LyricLoadState,
    direction: TrackTransitionDirection,
): LyricPresentationRequest = LyricPresentationRequest(track.id, when (loadState) {
    LyricLoadState.LOADING -> null
    LyricLoadState.READY -> track.lyrics.takeIf { it.isNotEmpty() }?.let {
        LyricPresentation(track.id, track.source, track.durationMs, it)
    }
    LyricLoadState.UNAVAILABLE -> LyricPresentation(track.id, track.source, track.durationMs, emptyList())
}, direction)

private fun RapidTrackSwitchPresentation.lyricRequest() = lyricPresentationRequest(
    track,
    displayedLyricLoadState(true, lyricsAvailable, LyricLoadState.LOADING),
    direction,
)

internal fun lyricPresentationRequests(
    playback: Flow<LyricPresentationRequest>,
    preview: Flow<RapidTrackSwitchPresentation?>,
): Flow<LyricPresentationRequest> = combine(
    playback,
    preview.map { it?.lyricRequest() }.distinctUntilChanged(),
) { settled, pending -> pending ?: settled }.distinctUntilChanged()

internal fun lyricPlaybackRequests(state: Flow<MusicOneUiState>): Flow<LyricPresentationRequest> =
    state.mapNotNull { playback ->
        playback.currentTrack?.let { track ->
            lyricPresentationRequest(track, playback.lyricLoadState, playback.trackTransitionDirection)
        }
    }.distinctUntilChanged()

@Composable
internal fun rememberLyricPresentationRequest(
    track: MusicTrack,
    loadState: LyricLoadState,
    direction: TrackTransitionDirection,
    rapidSwitch: RapidTrackSwitch,
    playbackState: StateFlow<MusicOneUiState>,
): State<LyricPresentationRequest> {
    val requests = remember(rapidSwitch, playbackState) {
        // 直接读取播放状态，避免预览撤销时组合参数仍停留在上一首。
        lyricPresentationRequests(lyricPlaybackRequests(playbackState), rapidSwitch.presentation)
    }
    return requests.collectAsStateWithLifecycle(
        initialValue = rapidSwitch.presentation.value?.lyricRequest()
            ?: lyricPresentationRequest(track, loadState, direction),
    )
}

internal fun displayedLyricLoadState(
    rapidSwitching: Boolean,
    rapidLyricsAvailable: Boolean,
    settledState: LyricLoadState,
): LyricLoadState = when {
    !rapidSwitching -> settledState
    rapidLyricsAvailable -> LyricLoadState.READY
    // 未缓存歌词仍在等待，不能用空窗口提前消耗下一首的入场动画。
    else -> LyricLoadState.LOADING
}
