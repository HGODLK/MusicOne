package com.musicone

internal val PRELOAD_AUDIO_SOURCES: Set<MusicSource> get() = MusicSource.entries.filterTo(linkedSetOf()) {
    com.musicone.sourceintegration.SourcePackageController.current()?.running(it.id)?.entry?.mediaPlayback != null
}
internal val PRELOAD_LYRIC_SOURCES: Set<MusicSource> get() = MusicSource.entries.filterTo(linkedSetOf()) {
    com.musicone.sourceintegration.SourcePackageController.current()?.running(it.id)?.entry?.lyricProvider != null
}

/** 沿实际播放顺序遍历一轮，跳过本地文件，不跳回当前歌曲或重复准备同一资源。 */
internal fun nextPlaybackPreloadTracks(state: MusicOneUiState, sources: Set<MusicSource>, limit: Int): List<MusicTrack> {
    val current = state.currentTrack ?: return emptyList()
    if (state.qualityChanging || limit <= 0) return emptyList()
    var index = state.queue.indexOfFirst { it.id == current.id && it.source == current.source }
    if (index < 0) return emptyList()
    val seen = mutableSetOf(current.source to current.id)
    val visited = mutableSetOf(index)
    val targets = mutableListOf<MusicTrack>()
    var cursor = state
    repeat(state.queue.size) {
        val next = if (cursor.radioActive) (index + 1).takeIf { it < state.queue.size }
        else cursor.insertedNextIndex() ?: nextQueueIndex(index, state.queue.size, state.shuffle, state.repeatMode, true)
        if (next == null || !visited.add(next)) return targets
        index = next
        val track = state.queue[index]
        if (track.source in sources && track.playable && seen.add(track.source to track.id)) targets += track
        if (targets.size == limit) return targets
        cursor = cursor.copy(currentTrack = track, queueOrder = cursor.queueOrder.afterTrackChange(track.id))
    }
    return targets
}

/** 按实际码率估算二十秒并留文件头余量；短文件和完整缓存不重复下载。 */
internal fun playbackPreloadBytes(bitRate: Int, durationMs: Long = 0L): Long {
    val milliseconds = if (durationMs > 0L) minOf(20_000L, durationMs) else 20_000L
    return bitRate.coerceAtLeast(128_000).toLong() * milliseconds / 8_000L + 128 * 1024L
}

/** 当前歌真实播放即可开始探测；不再等十秒缓冲。 */
internal fun playbackPreloadCanProbe(playing: Boolean, paused: Boolean = false): Boolean = playing || paused

/** 仅当前媒体的有效暂停进入三首预取；错误、停止、结束和切歌中间态不算暂停。 */
internal fun playbackPreloadIsPaused(matches: Boolean, playing: Boolean, playWhenReady: Boolean,
    playbackState: Int, hasError: Boolean): Boolean =
    matches && !playing && !playWhenReady && !hasError &&
        (playbackState == androidx.media3.common.Player.STATE_READY ||
            playbackState == androidx.media3.common.Player.STATE_BUFFERING)

internal fun playbackPreloadProbeDue(index: Int, elapsedMs: Long, paused: Boolean): Boolean =
    if (paused) index in 0 until PLAYBACK_PRELOAD_PAUSED_DOWNLOAD_COUNT
    else elapsedMs >= playbackProbeDelayMs(index)

/** 下载仍需给当前播放留三秒缓冲；本地文件不受网络缓冲限制。 */
internal fun playbackPreloadCanDownload(playing: Boolean, bufferedMs: Long, local: Boolean = false,
    paused: Boolean = false): Boolean {
    if (paused) return true
    if (!playing) return false
    if (local) return true
    return bufferedMs >= PLAYBACK_PRELOAD_LOW_BUFFER_MS
}
