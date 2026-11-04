package com.musicone

import com.musicone.source.sdk.SourceLyricTiming
import org.junit.Assert.*
import org.junit.Test

class SourceLyricTimingCacheTest {
    @Test fun repeatedStateNotificationsReuseTimingButReplacementInstancesRefresh() {
        val cache = SourceLyricTimingCache()
        val track = MusicTrack(id = "timing", source = MusicSource.QQ, title = "歌词", artists = "歌手",
            album = "", durationMs = 1000L, artworkStart = 0, artworkEnd = 0, artworkMark = "", previewUrl = "")
        val owner = Any()
        var calls = 0
        fun read(value: MusicTrack = track, instance: Any? = owner) = cache.read(value, instance) {
            calls++
            SourceLyricTiming(calls.toLong(), 105L)
        }
        repeat(20) { assertEquals(SourceLyricTiming(1L, 105L), read()) }
        assertEquals(1, calls)
        assertEquals(2L, read(track.copy()).lineLeadMs)
        assertEquals(3L, read(instance = Any()).lineLeadMs)
        assertEquals(4L, read(instance = null).lineLeadMs)
        assertEquals(4L, read(instance = null).lineLeadMs)
    }
}
