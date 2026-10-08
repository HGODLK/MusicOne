package com.musicone.demo

/** 音源尚未装载时保留最新跳转；歌曲相同但播放请求不同也不能交接旧位置。 */
internal class PlaybackPendingSeek {
    private var pending: Pair<PlaybackRequestKey, Long>? = null

    fun request(key: PlaybackRequestKey, positionMs: Long) {
        pending = key to positionMs.coerceAtLeast(0L)
    }

    fun take(key: PlaybackRequestKey): Long? {
        val target = pending?.takeIf { it.first == key } ?: return null
        pending = null
        return target.second
    }

    fun clear() { pending = null }
}
