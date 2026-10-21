package com.musicone.demo

internal const val MAX_PLAYBACK_QUEUE_SIZE = 99_999

/** 超出容量时保留前段顺序，并确保当前或明确点选的歌曲仍在队列中。 */
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
