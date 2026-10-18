package com.musicone.demo

/** 把点播歌曲移到当前歌曲之后，同时保留原有的下一首与后续顺序。 */
internal fun insertTrackAfterCurrent(
    queue: List<MusicTrack>,
    current: MusicTrack?,
    selected: MusicTrack,
): List<MusicTrack> {
    fun MusicTrack.sameTrack(other: MusicTrack): Boolean = source == other.source && id == other.id

    val retained = queue.filterNot { it.sameTrack(selected) }.toMutableList()
    if (current != null && !current.sameTrack(selected) && retained.none { it.sameTrack(current) }) retained += current
    val currentIndex = current?.let { active -> retained.indexOfFirst { it.sameTrack(active) } } ?: -1
    retained.add((currentIndex + 1).coerceAtLeast(0), selected)
    return retained
}

/** 只改队列，不触碰当前歌曲、播放意图、歌词或进度。 */
internal fun MusicOneUiState.withTrackInsertedNext(selected: MusicTrack): MusicOneUiState {
    val current = currentTrack
    if (current?.source == selected.source && current.id == selected.id) return this
    return copy(
        queue = insertTrackAfterCurrent(queue, current, selected),
        queueOrder = queueOrder.insert(queue, current, selected).copy(
            nextAfterId = current?.id, nextTrackId = selected.id),
        qqRadioActive = qqRadioActive && selected.source == MusicSource.QQ,
    )
}

/** 显式插入优先于单曲循环一次，播放模式本身保持原值。 */
internal fun MusicOneUiState.insertedNextIndex(): Int? {
    if (currentTrack?.id != queueOrder.nextAfterId) return null
    return queue.indexOfFirst { it.id == queueOrder.nextTrackId }.takeIf { it >= 0 }
}

internal fun PlaybackQueueOrder.afterTrackChange(trackId: String): PlaybackQueueOrder =
    if (trackId == nextAfterId) this else copy(nextAfterId = null, nextTrackId = null)
