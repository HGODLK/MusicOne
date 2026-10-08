package com.musicone.demo

/** 随机模式沿用已打乱的可见队列；电台不绕回历史，短队列不重复预取。 */
internal fun nextQqLyricsPreloadTracks(state: MusicOneUiState): List<MusicTrack> {
    val current = state.currentTrack ?: return emptyList()
    if (current.source != MusicSource.QQ || state.qualityChanging ||
        !state.qqRadioActive && state.repeatMode == RepeatMode.ONE) return emptyList()
    val queue = state.queue
    var index = queue.indexOfFirst { it.id == current.id }
    if (index < 0) return emptyList()
    val selected = linkedSetOf(current.id)
    val targets = mutableListOf<MusicTrack>()
    repeat(queue.size - 1) {
        index = if (state.qqRadioActive) index + 1 else
            nextQueueIndex(index, queue.size, state.shuffle, state.repeatMode, automatic = true)
                ?: return targets
        val track = queue.getOrNull(index) ?: return targets
        if (track.source == MusicSource.QQ && track.playable && selected.add(track.id)) targets += track
        if (targets.size == 5) return targets
    }
    return targets
}
