package com.musicone.demo

/** 预取只追随当前队列，单曲循环和列表末尾停止时不请求其他歌曲。 */
internal fun nextQqPreloadTrack(state: MusicOneUiState): MusicTrack? {
    val current = state.currentTrack ?: return null
    if (current.source != MusicSource.QQ || state.qualityChanging) return null
    val next = if (state.qqRadioActive) nextQqRadioTrack(state.queue, current.id) else {
        val index = state.queue.indexOfFirst { it.id == current.id }
        if (index < 0) return null
        nextQueueIndex(index, state.queue.size, state.shuffle, state.repeatMode, automatic = true)
            ?.let(state.queue::getOrNull)
    }
    return next?.takeIf { it.source == MusicSource.QQ && it.id != current.id && it.playable }
}

/** 约十五秒音频另留文件头余量，无损也最多预取八 MiB。 */
internal fun qqAudioPreloadBytes(bitRate: Int): Long =
    (bitRate.coerceAtLeast(128_000).toLong() * 15 / 8 + 128 * 1024)
        .coerceIn(384 * 1024L, 8 * 1024 * 1024L)

internal fun canPreloadQqAudio(playing: Boolean, bufferedMs: Long, remainingMs: Long): Boolean =
    playing && (bufferedMs >= 10_000 || remainingMs in 1..10_000 && bufferedMs >= remainingMs - 250)

internal fun qqPreloadedSourceFresh(createdMs: Long, nowMs: Long): Boolean =
    nowMs - createdMs in 0 until 30_000
