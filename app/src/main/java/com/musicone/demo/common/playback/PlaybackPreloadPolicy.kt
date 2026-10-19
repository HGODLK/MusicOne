package com.musicone.demo

internal val PRELOAD_AUDIO_SOURCES = setOf(MusicSource.QQ, MusicSource.KUGOU, MusicSource.NETEASE, MusicSource.BILIBILI)
internal val PRELOAD_LYRIC_SOURCES = PRELOAD_AUDIO_SOURCES - MusicSource.BILIBILI

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
        val next = if (cursor.qqRadioActive) (index + 1).takeIf { it < state.queue.size }
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

internal fun preloadedPlaybackFresh(createdMs: Long, nowMs: Long): Boolean = nowMs - createdMs in 0 until 30_000L
