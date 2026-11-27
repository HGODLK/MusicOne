package com.musicone

/** 音频还没加载好时暂存用户的拖动进度，加载完再跳；如果不是同一次播放请求则作废。 */
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
