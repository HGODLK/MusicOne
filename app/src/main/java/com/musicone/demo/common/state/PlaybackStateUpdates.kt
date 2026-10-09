package com.musicone.demo

/** 子任务只发布结果，界面快照在统一入口合并，避免各控制器直接改写共享状态。 */
internal fun MusicOneUiState.withQualityEvent(event: PlaybackQualityEvent): MusicOneUiState = when (event) {
    is PlaybackQualityEvent.Loading -> copy(qualityLoading = true)
    is PlaybackQualityEvent.Options -> copy(
        availableQualities = verifiedPlayerQualities(activeQuality, event.values),
        qualityLoading = if (event.complete) false else qualityLoading,
        qualityError = if (event.complete) null else qualityError,
    )
    is PlaybackQualityEvent.OptionsFailed -> copy(qualityLoading = false, qualityError = event.message)
    is PlaybackQualityEvent.Changing -> copy(qualityChanging = true, qualityTarget = event.quality,
        qualityError = null, playbackMessage = null)
    is PlaybackQualityEvent.Selected -> copy(
        currentTrack = event.url?.let { currentTrack?.copy(previewUrl = it) } ?: currentTrack,
        activeQuality = event.quality, qualityChanging = false, qualityTarget = null, qualityError = null,
    )
    is PlaybackQualityEvent.ChangeFailed -> copy(qualityChanging = false, qualityTarget = null,
        qualityError = event.message, playbackMessage = event.message)
    is PlaybackQualityEvent.Upgraded -> {
        val track = currentTrack?.copy(previewUrl = event.source.url)
        if (track == null) this else copy(currentTrack = track, queue = queue.map { if (it.id == track.id) track else it },
            activeQuality = event.quality,
            availableQualities = verifiedPlayerQualities(event.source.actualQuality, availableQualities))
    }
}

internal fun MusicOneUiState.withLyricsUpdate(update: PlaybackLyricsUpdate): MusicOneUiState = copy(
    currentTrack = update.lyrics?.let { currentTrack?.copy(lyrics = it) } ?: currentTrack,
    lyricLoadState = update.state,
)

internal fun MusicOneUiState.withRadioEvent(event: QqRadioEvent): MusicOneUiState = when (event) {
    is QqRadioEvent.Mode -> if (event.active) copy(qqRadioActive = true, shuffle = false, repeatMode = RepeatMode.ALL)
        else copy(qqRadioActive = false, qqRadioLoading = false)
    is QqRadioEvent.Loading -> copy(qqRadioLoading = true, playbackMessage = null)
    is QqRadioEvent.Started -> copy(qqRadioActive = true, qqRadioLoading = false,
        shuffle = false, repeatMode = RepeatMode.ALL)
    is QqRadioEvent.Appended -> copy(queue = event.queue, qqRadioLoading = false)
    is QqRadioEvent.Failed -> copy(qqRadioActive = if (event.initial) false else qqRadioActive,
        qqRadioLoading = false, playbackMessage = event.message)
}

internal fun MusicOneUiState.withResolvedPlayback(event: PlaybackTrackEvent.Ready): MusicOneUiState {
    val resolved = event.playback
    val loadedLyrics = currentTrack?.takeIf { it.id == resolved.track.id }?.lyrics.orEmpty()
    val track = resolved.track.copy(lyrics = loadedLyrics.ifEmpty { resolved.track.lyrics })
    val verifiedSource = resolved.source.takeUnless { it.trial || it.verificationPending }
    return copy(currentTrack = track, queue = queue.map { if (it.id == track.id) track else it },
        isPlaying = event.playWhenReady,
        lyricLoadState = if (track.lyrics.isEmpty()) LyricLoadState.LOADING else LyricLoadState.READY,
        activeQuality = verifiedSource?.let { playerDisplayedQuality(event.preferred, it.actualQuality) },
        availableQualities = verifiedSource?.let { listOf(it.actualQuality) }.orEmpty(), playbackMessage = null)
}

internal fun MusicOneUiState.withPlaybackMetadata(event: PlaybackTrackEvent.Metadata): MusicOneUiState {
    val live = currentTrack ?: return this
    val track = event.enriched.copy(previewUrl = live.previewUrl.ifBlank { event.original.previewUrl }, lyrics = live.lyrics)
    return copy(currentTrack = track, queue = queue.map { if (it.id == track.id) track else it })
}
