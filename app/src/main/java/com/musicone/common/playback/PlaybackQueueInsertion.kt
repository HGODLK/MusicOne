package com.musicone

/** 把点播歌曲移到当前歌曲之后，同时保留原有的下一首与后续顺序。 */
internal fun insertTrackAfterCurrent(
    queue: List<MusicTrack>,
    current: MusicTrack?,
    selected: MusicTrack,
    capacity: Int = MAX_PLAYBACK_QUEUE_SIZE,
): List<MusicTrack> {
    fun MusicTrack.sameTrack(other: MusicTrack): Boolean = source == other.source && id == other.id

    var retained = queue.filterNot { it.sameTrack(selected) }.toMutableList()
    if (current != null && !current.sameTrack(selected) && retained.none { it.sameTrack(current) }) retained += current
    if (retained.size >= capacity) retained = limitPlaybackQueue(retained, current, capacity - 1).toMutableList()
    val currentIndex = current?.let { active -> retained.indexOfFirst { it.sameTrack(active) } } ?: -1
    retained.add((currentIndex + 1).coerceAtLeast(0), selected)
    return retained
}

/** 只把歌插到下一首位置，不影响当前播放、歌词和进度。 */
internal fun MusicOneUiState.withTrackInsertedNext(selected: MusicTrack): MusicOneUiState {
    val current = currentTrack
    if (current?.source == selected.source && current.id == selected.id) return this
    val nextQueue = insertTrackAfterCurrent(queue, current, selected)
    return copy(
        queue = nextQueue,
        queueOrder = queueOrder.insert(queue, current, selected).reconcile(nextQueue).copy(
            nextAfterId = current?.id, nextTrackId = selected.id),
        radioActive = radioActive && selected.source == current?.source,
    )
}

/** 单曲循环时如果手动插了“下一首播放”，下一次先播插进来的歌，不改播放模式。 */
internal fun MusicOneUiState.insertedNextIndex(): Int? {
    if (currentTrack?.id != queueOrder.nextAfterId) return null
    return queue.indexOfFirst { it.id == queueOrder.nextTrackId }.takeIf { it >= 0 }
}

internal fun PlaybackQueueOrder.afterTrackChange(trackId: String): PlaybackQueueOrder =
    if (trackId == nextAfterId) this else copy(nextAfterId = null, nextTrackId = null)
