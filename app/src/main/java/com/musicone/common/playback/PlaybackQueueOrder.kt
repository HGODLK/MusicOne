package com.musicone

import kotlin.random.Random

/** 维护队列顺序：随机播放时额外保存一份原始顺序，退出随机时再恢复。 */
data class PlaybackQueueOrder(
    val originalIds: List<String> = emptyList(),
    val nextAfterId: String? = null,
    val nextTrackId: String? = null,
) {
    fun restore(queue: List<MusicTrack>): List<MusicTrack> {
        if (originalIds.isEmpty()) return queue
        val remaining = queue.associateByTo(linkedMapOf(), MusicTrack::id)
        return buildList {
            originalIds.forEach { id -> remaining.remove(id)?.let(::add) }
            addAll(remaining.values)
        }
    }

    fun reconcile(queue: List<MusicTrack>): PlaybackQueueOrder =
        copy(originalIds = restore(queue).map(MusicTrack::id))

    fun insert(queue: List<MusicTrack>, current: MusicTrack?, selected: MusicTrack): PlaybackQueueOrder =
        // 原始顺序只同步删除实际队列里被挤掉的歌，不再单独做一次末尾截断。
        copy(originalIds = insertTrackAfterCurrent(restore(queue), current, selected,
            capacity = Int.MAX_VALUE).map(MusicTrack::id))
}

internal fun MusicOneUiState.withPlaybackMode(
    mode: PlayerPlaybackMode,
    random: Random = Random.Default,
): MusicOneUiState {
    val wasShuffle = shuffle
    val willShuffle = mode == PlayerPlaybackMode.SHUFFLE
    val order = if (wasShuffle) queueOrder.reconcile(queue) else PlaybackQueueOrder(queue.map(MusicTrack::id))
    val nextQueue = when {
        willShuffle && !wasShuffle -> {
            val current = queue.firstOrNull { it.id == currentTrack?.id }
            // 当前这首保持在第一位，后面的歌在刚切到随机模式时打乱一次。
            listOfNotNull(current) + queue.filterNot { it.id == current?.id }.shuffled(random)
        }
        !willShuffle && wasShuffle -> order.restore(queue)
        else -> queue
    }
    return copy(queue = nextQueue, queueOrder = order, shuffle = willShuffle,
        repeatMode = if (mode == PlayerPlaybackMode.SINGLE) RepeatMode.ONE else RepeatMode.ALL)
}

internal fun MusicOneUiState.withPlaylistQueue(
    tracks: List<MusicTrack>,
    shuffle: Boolean,
    random: Random = Random.Default,
    resetRepeatMode: Boolean = false,
    selectedTrack: MusicTrack? = null,
): MusicOneUiState {
    val original = limitPlaybackQueue(tracks.distinctBy(MusicTrack::id), selectedTrack)
    return copy(queue = if (shuffle) original.shuffled(random) else original,
        queueOrder = PlaybackQueueOrder(original.map(MusicTrack::id)),
        shuffle = shuffle, repeatMode = if (resetRepeatMode) RepeatMode.ALL else repeatMode)
}
