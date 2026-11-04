package com.musicone

internal fun miniPlayerNeighbor(state: MusicOneUiState, history: PlaybackHistory, next: Boolean): MusicTrack? {
    val current = state.currentTrack ?: return null
    val queue = state.queue
    val index = queue.indexOfFirst { it.id == current.id }
    if (index < 0 || queue.size < 2) return null
    if (state.radioActive) return if (next) nextSourceRadioTrack(queue, current.id) else previousSourceRadioTrack(queue, current.id)
    if (!next && state.shuffle) {
        val id = history.peekPrevious(queue.mapTo(hashSetOf(), MusicTrack::id))
        queue.firstOrNull { it.id == id }?.let { return it }
    }
    val target = if (next) nextQueueIndex(index, queue.size, state.shuffle, state.repeatMode, false)
        else (index - 1 + queue.size) % queue.size
    return target?.let(queue::get)
}
