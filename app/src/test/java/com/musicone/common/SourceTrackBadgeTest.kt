package com.musicone

import com.musicone.source.sdk.SourceTrackBadge
import com.musicone.source.sdk.TrackAccessBadge
import com.musicone.source.sdk.TrackAccessInfo
import com.musicone.sourceintegration.toMusicTrack
import com.musicone.sourceintegration.toSourceTrack
import org.junit.Assert.*
import org.junit.Test

class SourceTrackBadgeTest {
    @Test fun customTextAndBothThemesSurviveStorageAndSdkRoundTrip() {
        val badge = SourceTrackBadge("独家现场", 0xFF123456L, 0x22654321L, 0xFFEEDDCCL, 0L)
        val track = MusicTrack("third-party-track", MusicSource.fromId("org.example.source"),
            "歌曲", "歌手", "专辑", 1000, 0, 0, "", "", displayBadge = badge)
        val stored = track.toStoredTrackJson().toStoredTrack()!!
        assertEquals(badge, stored.displayBadge)
        assertEquals(badge, stored.toSourceTrack().toMusicTrack().displayBadge)
        assertEquals(track.id, stored.id)
        assertEquals(badge, resolvedSongAccessBadge(TrackAccessInfo(displayBadge = stored.displayBadge)))
    }

    @Test fun legacyRecordsAndUndeclaredColorsRetainDefaultAccessPresentation() {
        val original = MusicTrack("test-song", MusicSource.QQ, "歌曲", "歌手", "", 1000, 0, 0, "", "",
            accessBadge = MusicAccessBadge.PAID)
        val restored = original.toStoredTrackJson().toStoredTrack()!!
        assertNull(restored.displayBadge)
        val defaults = resolvedSongAccessBadge(TrackAccessInfo(TrackAccessBadge.PURCHASE))!!
        assertEquals("付费", defaults.text)
        assertEquals(0xFFE16055L, defaults.lightTextArgb)
        val declared = resolvedSongAccessBadge(TrackAccessInfo(TrackAccessBadge.PURCHASE,
            displayBadge = SourceTrackBadge("试听", darkTextArgb = 0xFF112233L)))!!
        assertEquals("试听", declared.text)
        assertEquals(defaults.lightTextArgb, declared.lightTextArgb)
        assertEquals(0xFF112233L, declared.darkTextArgb)
        assertNull(resolvedSongAccessBadge(TrackAccessInfo(displayBadge = SourceTrackBadge(""))))
    }
}
