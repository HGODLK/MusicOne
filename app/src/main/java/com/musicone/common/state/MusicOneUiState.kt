package com.musicone

enum class MusicOnePage { HOME, MY }

data class MusicOneUiState(
    val page: MusicOnePage = MusicOnePage.HOME,
    val currentTrack: MusicTrack? = null,
    val queue: List<MusicTrack> = emptyList(),
    val queueOrder: PlaybackQueueOrder = PlaybackQueueOrder(),
    val isPlaying: Boolean = false,
    val playerExpanded: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.ALL,
    val activeQuality: AudioQuality? = null,
    val availableQualities: List<AudioQuality> = emptyList(),
    val qualityLoading: Boolean = false,
    val qualityChanging: Boolean = false,
    val qualityTarget: AudioQuality? = null,
    val qualityError: String? = null,
    val lyricLoadState: LyricLoadState = LyricLoadState.UNAVAILABLE,
    val radioActive: Boolean = false,
    val radioLoading: Boolean = false,
    val playbackMessage: String? = null,
    val trackTransitionDirection: TrackTransitionDirection = TrackTransitionDirection.NEXT,
)

internal fun MusicOneUiState.forTrackTransition(
    track: MusicTrack,
    nextQueue: List<MusicTrack> = queue,
    playWhenReady: Boolean,
    direction: TrackTransitionDirection = TrackTransitionDirection.NEXT,
): MusicOneUiState = copy(
    currentTrack = track,
    queue = nextQueue,
    queueOrder = queueOrder.afterTrackChange(track.id),
    isPlaying = playWhenReady,
    availableQualities = emptyList(),
    qualityLoading = false,
    qualityChanging = false,
    qualityTarget = null,
    qualityError = null,
    lyricLoadState = if (track.lyrics.isEmpty()) LyricLoadState.LOADING else LyricLoadState.READY,
    playbackMessage = null,
    trackTransitionDirection = direction,
)

internal fun isCurrentPlaybackRequest(
    requestGeneration: Long,
    currentGeneration: Long,
    requestTrackId: String,
    currentTrackId: String?,
): Boolean = requestGeneration == currentGeneration && requestTrackId == currentTrackId

/** 包声明等待交接时，解析完成前不提前切走当前歌词窗口。 */
internal fun MusicTrack.forPendingPlaybackPresentation(
    waitForSettlement: Boolean = source.lyricBehavior?.waitForSingleSwitchSettlement == true,
): MusicTrack = if (waitForSettlement && lyrics.isNotEmpty()) copy(lyrics = emptyList()) else this

