package com.musicone.demo

import kotlin.random.Random

/** 播放列表就是实际播放顺序，另存原始身份顺序供退出随机模式时恢复。 */
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
        // 原始顺序只按实际队列淘汰的歌曲收敛，不能再按原始末尾淘汰一次。
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
            // 当前曲继续播放，后续曲目只在进入随机模式时洗牌一次。
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
