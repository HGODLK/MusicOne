package com.musicone

internal val PRELOAD_AUDIO_SOURCES: Set<MusicSource> get() = MusicSource.entries.filterTo(linkedSetOf()) {
    com.musicone.sourceintegration.SourcePackageController.current()?.running(it.id)?.entry?.mediaPlayback != null
}
internal val PRELOAD_LYRIC_SOURCES: Set<MusicSource> get() = MusicSource.entries.filterTo(linkedSetOf()) {
    com.musicone.sourceintegration.SourcePackageController.current()?.running(it.id)?.entry?.lyricProvider != null
}

/** 按实际播放顺序挑出后面需要预加载的在线歌曲，跳过本地文件，不重复也不绕回当前这首。 */
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

/** 当前歌曲开始播（或有效暂停）就可以启动音质探测，不用等缓冲满 10 秒。 */
internal fun playbackPreloadCanProbe(playing: Boolean, paused: Boolean = false): Boolean = playing || paused

/** 只有在正常暂停状态下才触发暂停预加载，报错、停止、播完或切歌途中都不算。 */
internal fun playbackPreloadIsPaused(matches: Boolean, playing: Boolean, playWhenReady: Boolean,
    playbackState: Int, hasError: Boolean): Boolean =
    matches && !playing && !playWhenReady && !hasError &&
        (playbackState == androidx.media3.common.Player.STATE_READY ||
            playbackState == androidx.media3.common.Player.STATE_BUFFERING)

internal fun playbackPreloadProbeDue(index: Int, elapsedMs: Long, paused: Boolean): Boolean =
    if (paused) index in 0 until PLAYBACK_PRELOAD_PAUSED_DOWNLOAD_COUNT
    else elapsedMs >= playbackProbeDelayMs(index)

/** 边播边预加载时，先保证当前歌至少有 3 秒缓冲再开下；本地文件不受此限制。 */
internal fun playbackPreloadCanDownload(playing: Boolean, bufferedMs: Long, local: Boolean = false,
    paused: Boolean = false): Boolean {
    if (paused) return true
    if (!playing) return false
    if (local) return true
    return bufferedMs >= PLAYBACK_PRELOAD_LOW_BUFFER_MS
}
