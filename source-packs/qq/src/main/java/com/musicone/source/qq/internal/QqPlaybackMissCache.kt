package com.musicone.source.qq.internal

/**
 * 记住 CDN 明确返回 404/410 的音质文件。文件是否存在与账号无关，
 * 下次点播、重试或音质探测直接跳过该档，不再换票和探测。
 */
internal class QqPlaybackMissCache(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val ttlMs: Long = 24 * 60 * 60_000L,
) {
    private data class Key(val songMid: String, val mediaMid: String, val prefix: String)
    private val entries = object : LinkedHashMap<Key, Long>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Long>?): Boolean = size > 512
    }

    @Synchronized
    fun contains(track: MusicTrack, quality: AudioQuality, spec: QqQualitySpec): Boolean {
        val key = key(track, quality, spec)
        val expiresAt = entries[key] ?: return false
        if (clockMs() < expiresAt) return true
        entries.remove(key)
        return false
    }

    @Synchronized
    fun remember(track: MusicTrack, quality: AudioQuality, spec: QqQualitySpec) {
        entries[key(track, quality, spec)] = clockMs() + ttlMs
    }

    private fun key(track: MusicTrack, quality: AudioQuality, spec: QqQualitySpec) =
        Key(track.qqPlaybackMid(), track.qqPlaybackMediaMid(quality), spec.prefix)

    companion object {
        // 同一进程内的多个客户端共享，音质探测发现的缺失也能让点播受益。
        val shared = QqPlaybackMissCache()
    }
}

/** 该档文件在 CDN 上不存在；不是网络故障，不阻止继续降档。 */
internal class QqPlaybackFileMissingException(quality: AudioQuality) :
    PlatformApiException("QQ 音乐暂无${quality.displayLabel(MusicSource.QQ)}文件")
