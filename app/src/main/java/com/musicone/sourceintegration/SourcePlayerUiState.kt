package com.musicone.sourceintegration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

internal fun sourcePlayerSnapshot(state: MusicOneUiState, favorite: Boolean,
    artworkColors: List<Int>? = null): SourcePlayerSnapshot {
    val source = state.currentTrack?.source ?: MusicSource.LOCAL
    return SourcePlayerSnapshot(state.currentTrack?.toSourceTrack(), state.isPlaying, state.shuffle,
        state.repeatMode.name, sourcePlayerQualityOptions(state).map {
            SourcePlayerQuality(it.qualityId, it.displayLabel(source), it.displayCompactLabel(source))
        }, state.activeQuality?.qualityId, state.qualityLoading, state.qualityChanging,
        state.qualityError ?: state.playbackMessage, favorite, artworkColors)
}

/** 只返回当前歌曲封面的色场；加载期间画面沿用旧封面时，不把旧歌颜色当成新歌。 */
@Composable
internal fun sourcePlayerArtworkColors(track: MusicTrack?): List<Int>? {
    if (track == null) return null
    val frame by rememberPlayerArtwork(track)
    val identity = track.artworkIdentity()
    val field = ArtworkColorFieldRepository.peek(identity)
        ?: frame.takeIf { it.identity == identity }?.let { ArtworkColorFieldRepository.prepare(identity, it.bitmap) }
    return remember(field) { field?.toList() }
}
