package com.musicone.demo

/** 短期复用已验证完整音源，避免起播后查询音质再次换取同一张票。 */
internal class QqPlaybackSourceCache(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class Key(
        val credential: String,
        val songMid: String,
        val mediaMid: String,
        val songType: Int,
        val durationMs: Long,
        val quality: AudioQuality,
    )
    private data class Entry(val source: PlaybackSource, val expiresAt: Long)
    private val entries = object : LinkedHashMap<Key, Entry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?): Boolean = size > 8
    }

    @Synchronized
    fun get(track: MusicTrack, cookie: String, quality: AudioQuality): PlaybackSource? {
        val key = key(track, cookie, quality)
        val entry = entries[key] ?: return null
        if (clockMs() >= entry.expiresAt) {
            entries.remove(key)
            return null
        }
        return entry.source
    }

    @Synchronized
    fun remember(track: MusicTrack, cookie: String, source: PlaybackSource) {
        if (source.trial || source.verificationPending) return
        entries[key(track, cookie, source.actualQuality)] = Entry(source, clockMs() + 30_000L)
    }

    private fun key(track: MusicTrack, cookie: String, quality: AudioQuality) = Key(
        cookie, track.qqPlaybackMid(), track.mediaId, track.providerType, track.durationMs, quality,
    )
}
