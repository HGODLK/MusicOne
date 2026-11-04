package com.musicone

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
    queue = update.lyrics?.let { lyrics -> queue.map {
        if (it.id == update.key.trackId && it.source == currentTrack?.source) it.copy(lyrics = lyrics) else it
    } } ?: queue,
    lyricLoadState = update.state,
)

internal fun MusicOneUiState.withResolvedPlayback(event: PlaybackTrackEvent.Ready): MusicOneUiState {
    val resolved = event.playback
    val loadedLyrics = currentTrack?.takeIf { it.id == resolved.track.id }?.lyrics.orEmpty()
    val preferredLyrics = loadedLyrics.takeIf { hasReusableLyrics(resolved.track, it) }
        ?: resolved.track.lyrics.ifEmpty { loadedLyrics }
    val track = resolved.track.copy(lyrics = preferredLyrics)
    val verifiedSource = resolved.source.takeUnless { it.trial || it.verificationPending }
    resolved.source.actualFormat?.let { PlaybackAudioInfo.remember(resolved.source.url, it,
        mergeDecoded = resolved.source.mimeType == "application/dash+xml", bitrate = resolved.source.bitRate) }
    return copy(currentTrack = track, queue = queue.map { if (it.id == track.id) track else it },
        lyricLoadState = when {
            track.lyrics.isNotEmpty() -> LyricLoadState.READY
            track.source.supportsLibraryEditing -> lyricLoadState
            else -> LyricLoadState.LOADING
        },
        activeQuality = verifiedSource?.let { playerDisplayedQuality(event.preferred, it.actualQuality) },
        availableQualities = verifiedSource?.let { listOf(it.actualQuality) }.orEmpty(),
        qualityChanging = false, qualityTarget = null, playbackMessage = null)
}

internal fun MusicOneUiState.withPlaybackMetadata(event: PlaybackTrackEvent.Metadata): MusicOneUiState {
    val live = currentTrack ?: return this
    val track = event.enriched.copy(previewUrl = live.previewUrl.ifBlank { event.original.previewUrl }, lyrics = live.lyrics)
    return copy(currentTrack = track, queue = queue.map { if (it.id == track.id) track else it })
}

/** 队列无可恢复项后结束歌词等待，不能把上一首窗口一直留在失败目标上。 */
internal fun MusicOneUiState.withExhaustedPlaybackQueue(message: String): MusicOneUiState = copy(
    isPlaying = false,
    qualityLoading = false,
    qualityChanging = false,
    lyricLoadState = if (currentTrack?.lyrics.isNullOrEmpty()) LyricLoadState.UNAVAILABLE else LyricLoadState.READY,
    playbackMessage = message,
)
