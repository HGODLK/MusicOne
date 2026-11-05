package com.musicone

/** 当前歌最终失败后的去向：跳到下一首、连续失败达到上限后暂停，或队列已无可播歌曲。 */
internal sealed interface PlaybackFailureOutcome {
    data class Skip(val next: MusicTrack) : PlaybackFailureOutcome
    data object LimitReached : PlaybackFailureOutcome
    data object Exhausted : PlaybackFailureOutcome
}

/**
 * 一轮失败遍历最多访问队列中的每首一次；真正开始出声或用户操作后清零。
 * 播放器中途出错的续播次数取宿主下限与音源包声明的较大值。
 */
internal class PlaybackFailureRecovery(
    private val consecutiveLimit: Int = PLAYBACK_CONSECUTIVE_FAILURE_LIMIT,
) {
    private val failed = linkedSetOf<String>()
    private val renewed = mutableMapOf<String, Int>()
    fun reset() { failed.clear(); renewed.clear() }
    fun mayRenew(track: MusicTrack, limit: Int = maxOf(PLAYER_ERROR_RENEW_LIMIT,
        track.source.playbackBehavior?.renewOnPlayerErrorLimit ?: 0)): Boolean {
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
    /** 记录当前歌失败并决定去向；连续失败计数包含当前这首。 */
    fun fail(current: MusicTrack, queue: List<MusicTrack>, available: (MusicTrack) -> Boolean = { true }): PlaybackFailureOutcome {
        val next = next(current, queue, available)
        return when {
            failed.size >= consecutiveLimit -> PlaybackFailureOutcome.LimitReached
            next == null -> PlaybackFailureOutcome.Exhausted
            else -> PlaybackFailureOutcome.Skip(next)
        }
    }
}
