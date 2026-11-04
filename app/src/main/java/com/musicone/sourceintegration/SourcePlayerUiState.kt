package com.musicone.sourceintegration

import com.musicone.*
import com.musicone.source.sdk.*

/** 只有明确未支持的形态使用原生页；加载异常不是未支持。 */
internal fun SourcePlayerUiDeclaration.supports(form: SourcePlayerForm): Boolean = when (form) {
    SourcePlayerForm.PHONE_PORTRAIT -> supportsPhone
    SourcePlayerForm.PHONE_LANDSCAPE -> supportsPhone && supportsPhoneLandscape
    SourcePlayerForm.TABLET -> supportsTablet
}

internal fun sourcePlayerSeekMatches(trackId: String, generation: Long,
    currentTrackId: String?, progress: PlaybackProgressSnapshot): Boolean =
    trackId.isNotBlank() && trackId == currentTrackId && trackId == progress.trackId && generation == progress.generation

internal fun sourcePlayerTimeline(state: MusicOneUiState, progress: PlaybackProgressSnapshot,
    activity: PlaybackActivity): SourcePlayerTimeline {
    val track = state.currentTrack
    val matches = track != null && progress.trackId == track.id
    return SourcePlayerTimeline(track?.id.orEmpty(), if (matches) progress.positionMs else 0L,
        track?.durationMs ?: 0L, progress.generation,
        matches && activity.trackId == track?.id && activity.advancing)
}

internal fun sourcePlayerQualityOptions(state: MusicOneUiState): List<AudioQuality> =
    visiblePlayerQualities(state.availableQualities)

internal fun sourcePlayerSnapshot(state: MusicOneUiState, favorite: Boolean): SourcePlayerSnapshot {
    val source = state.currentTrack?.source ?: MusicSource.LOCAL
    return SourcePlayerSnapshot(state.currentTrack?.toSourceTrack(), state.isPlaying, state.shuffle,
        state.repeatMode.name, sourcePlayerQualityOptions(state).map {
            SourcePlayerQuality(it.qualityId, it.displayLabel(source), it.displayCompactLabel(source))
        }, state.activeQuality?.qualityId, state.qualityLoading, state.qualityChanging,
        state.qualityError ?: state.playbackMessage, favorite)
}
