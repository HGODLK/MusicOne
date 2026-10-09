package com.musicone.demo

internal const val QQ_RADIO_HISTORY_LIMIT = 30
internal const val QQ_RADIO_LOOKAHEAD = 5

internal fun qqRadioMissingLookAhead(
    queue: List<MusicTrack>,
    currentTrackId: String?,
    lookAhead: Int = QQ_RADIO_LOOKAHEAD,
): Int {
    val currentIndex = queue.indexOfFirst { it.id == currentTrackId }
    if (currentIndex < 0) return 0
    return (lookAhead - (queue.lastIndex - currentIndex)).coerceAtLeast(0)
}

internal fun appendQqRadioTracks(
    queue: List<MusicTrack>,
    additions: List<MusicTrack>,
): List<MusicTrack> = (queue + additions).distinctBy(MusicTrack::id)

internal fun nextQqRadioTrack(queue: List<MusicTrack>, currentTrackId: String?): MusicTrack? {
    val currentIndex = queue.indexOfFirst { it.id == currentTrackId }
    return currentIndex.takeIf { it >= 0 }?.let { queue.getOrNull(it + 1) }
}

internal fun previousQqRadioTrack(queue: List<MusicTrack>, currentTrackId: String?): MusicTrack? {
    val currentIndex = queue.indexOfFirst { it.id == currentTrackId }
    return currentIndex.takeIf { it > 0 }?.let { queue[it - 1] }
}

internal fun centerQqRadioQueue(
    queue: List<MusicTrack>,
    currentTrackId: String?,
    historyLimit: Int = QQ_RADIO_HISTORY_LIMIT,
    lookAhead: Int = QQ_RADIO_LOOKAHEAD,
): List<MusicTrack> {
    val distinct = queue.distinctBy(MusicTrack::id)
    val currentIndex = distinct.indexOfFirst { it.id == currentTrackId }
    if (currentIndex < 0) return distinct.take(lookAhead + 1)
    val from = (currentIndex - historyLimit).coerceAtLeast(0)
    val until = (currentIndex + lookAhead + 1).coerceAtMost(distinct.size)
    return distinct.subList(from, until).toList()
}

/** 保存猜你喜欢会话中已出现的歌曲，避免滑出 30 首历史窗口后又被推荐回来。 */
internal class QqRadioQueueWindow(
    private val historyLimit: Int = QQ_RADIO_HISTORY_LIMIT,
    private val lookAhead: Int = QQ_RADIO_LOOKAHEAD,
) {
    private val seenTrackIds = linkedSetOf<String>()

    val initialRequestSize: Int
        get() = lookAhead + 1

    fun reset() = seenTrackIds.clear()

    fun start(tracks: List<MusicTrack>, initialTrack: MusicTrack? = null): List<MusicTrack> {
        reset()
        val all = if (initialTrack != null) {
            (listOf(initialTrack) + tracks).distinctBy(MusicTrack::id)
        } else {
            tracks.distinctBy(MusicTrack::id)
        }
        val queue = all.take(initialRequestSize)
        remember(queue)
        return queue
    }

    fun insert(queue: List<MusicTrack>, current: MusicTrack?, selected: MusicTrack): List<MusicTrack> {
        remember(listOf(selected))
        return center(insertQqFeedTrack(queue, current, selected), selected.id)
    }

    fun restore(queue: List<MusicTrack>, currentTrackId: String?): List<MusicTrack> {
        reset()
        remember(queue)
        return center(queue, currentTrackId)
    }

    fun requestSize(queue: List<MusicTrack>, currentTrackId: String?, advanceAfterLoad: Boolean): Int {
        if (queue.none { it.id == currentTrackId }) return 0
        val missing = qqRadioMissingLookAhead(queue, currentTrackId, lookAhead)
        return missing + if (advanceAfterLoad) 1 else 0
    }

    fun append(
        queue: List<MusicTrack>,
        additions: List<MusicTrack>,
        currentTrackId: String?,
    ): List<MusicTrack> {
        remember(additions)
        return center(appendQqRadioTracks(queue, additions), currentTrackId)
    }

    fun center(queue: List<MusicTrack>, currentTrackId: String?): List<MusicTrack> =
        centerQqRadioQueue(queue, currentTrackId, historyLimit, lookAhead)

    fun seenIds(): Set<String> = seenTrackIds.toSet()

    private fun remember(tracks: List<MusicTrack>) {
        tracks.mapTo(seenTrackIds, MusicTrack::id)
    }
}
