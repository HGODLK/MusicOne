package com.musicone

internal const val MAX_PLAYBACK_QUEUE_SIZE = 99_999

/** 队列超长时截断末尾，但保证当前播放的歌或刚点选的歌不会被截掉。 */
internal fun limitPlaybackQueue(
    queue: List<MusicTrack>,
    requiredTrack: MusicTrack? = null,
    capacity: Int = MAX_PLAYBACK_QUEUE_SIZE,
): List<MusicTrack> {
    if (queue.size <= capacity) return queue
    val retained = queue.take(capacity)
    return if (requiredTrack == null || retained.any { it.id == requiredTrack.id }) retained
        else retained.dropLast(1) + requiredTrack
}

internal fun appendPlaybackTrack(queue: List<MusicTrack>, track: MusicTrack): List<MusicTrack> =
    if (queue.any { it.id == track.id }) queue else queue.take(MAX_PLAYBACK_QUEUE_SIZE - 1) + track
