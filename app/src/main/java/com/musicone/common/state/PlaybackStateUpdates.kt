package com.musicone

/** 各个后台任务只发事件，统一在这里把事件合并进 UI 状态，避免到处直接改状态。 */
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

/** 播放链接过期静默重拉后只换地址，不动歌词、音质列表和播放状态，避免界面闪烁。 */
internal fun MusicOneUiState.withRenewedPlayback(resolved: ResolvedPlayback): MusicOneUiState {
    val current = currentTrack ?: return this
    if (current.id != resolved.track.id || current.source != resolved.track.source) return this
    val updated = current.copy(previewUrl = resolved.source.url)
    resolved.source.actualFormat?.let {
        PlaybackAudioInfo.remember(
            resolved.source.url,
            it,
            mergeDecoded = resolved.source.mimeType == "application/dash+xml",
            bitrate = resolved.source.bitRate,
        )
    }
    return copy(
        currentTrack = updated,
        queue = queue.map { if (it.id == updated.id && it.source == updated.source) updated else it },
    )
}

/** 整条队列都播不了时结束歌词等待状态，别一直卡着上一首歌的歌词。 */
internal fun MusicOneUiState.withExhaustedPlaybackQueue(message: String): MusicOneUiState = copy(
    isPlaying = false,
    qualityLoading = false,
    qualityChanging = false,
    lyricLoadState = if (currentTrack?.lyrics.isNullOrEmpty()) LyricLoadState.UNAVAILABLE else LyricLoadState.READY,
    playbackMessage = message,
)
