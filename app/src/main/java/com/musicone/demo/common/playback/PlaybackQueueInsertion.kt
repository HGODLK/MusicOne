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
