package com.musicone.demo

/** 一轮失败遍历最多访问队列中的每首一次，B站播放器错误只重新取票一次。 */
internal class PlaybackFailureRecovery {
    private val failed = linkedSetOf<String>()
    private val renewed = hashSetOf<String>()
    fun reset() { failed.clear(); renewed.clear() }
    fun mayRenew(track: MusicTrack) = track.source == MusicSource.BILIBILI && renewed.add(track.id)
    fun next(current: MusicTrack, queue: List<MusicTrack>): MusicTrack? {
        if (queue.isEmpty()) return null
        failed += current.id
        val start = queue.indexOfFirst { it.id == current.id }
        return (1..queue.size).asSequence().map { queue[(start + it).mod(queue.size)] }
            .firstOrNull { it.id !in failed && it.playable }
    }
}
