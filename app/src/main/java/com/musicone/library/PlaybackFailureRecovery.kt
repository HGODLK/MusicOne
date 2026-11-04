package com.musicone

/** 一轮失败遍历最多访问队列中的每首一次，取票恢复次数由包声明。 */
internal class PlaybackFailureRecovery {
    private val failed = linkedSetOf<String>()
    private val renewed = mutableMapOf<String, Int>()
    fun reset() { failed.clear(); renewed.clear() }
    fun mayRenew(track: MusicTrack, limit: Int = track.source.playbackBehavior?.renewOnPlayerErrorLimit ?: 0): Boolean {
        val count = renewed[track.id] ?: 0
        if (count >= limit.coerceAtLeast(0)) return false
        renewed[track.id] = count + 1
        return true
    }
    fun next(current: MusicTrack, queue: List<MusicTrack>, available: (MusicTrack) -> Boolean = { true }): MusicTrack? {
        if (queue.isEmpty()) return null
        failed += current.id
        val start = queue.indexOfFirst { it.id == current.id }
        return (1..queue.size).asSequence().map { queue[(start + it).mod(queue.size)] }
            .firstOrNull { it.id !in failed && it.playable && available(it) }
    }
}
