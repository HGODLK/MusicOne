package com.musicone.demo

/** 一轮失败遍历最多访问队列中的每首一次；B站错误和网易云 HTTP 状态错误各重取一次地址。 */
internal class PlaybackFailureRecovery {
    private val failed = linkedSetOf<String>()
    private val renewed = hashSetOf<String>()
    fun reset() { failed.clear(); renewed.clear() }
    fun mayRenew(track: MusicTrack, httpStatusFailure: Boolean = false) = when (track.source) {
        MusicSource.BILIBILI -> renewed.add(track.id)
        MusicSource.NETEASE -> httpStatusFailure && renewed.add(track.id)
        else -> false
    }
    fun next(current: MusicTrack, queue: List<MusicTrack>): MusicTrack? {
        if (queue.isEmpty()) return null
        failed += current.id
        val start = queue.indexOfFirst { it.id == current.id }
        return (1..queue.size).asSequence().map { queue[(start + it).mod(queue.size)] }
            .firstOrNull { it.id !in failed && it.playable }
    }
}
