package com.musicone.demo

import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class HomeDailyRefreshTest {
    @Test fun automaticRefreshUsesLocalMidnightInsteadOfElapsedHours() {
        val zone = TimeZone.getTimeZone("Asia/Singapore")
        val before = homeRefreshDay(Instant.parse("2026-10-06T15:59:59Z").toEpochMilli(), zone)
        val after = homeRefreshDay(Instant.parse("2026-10-06T16:00:00Z").toEpochMilli(), zone)
        assertEquals("2026-10-06", before)
        assertEquals("2026-10-07", after)
        assertTrue(homeDailyRefreshDue(false, true, before, after))
        assertFalse(homeDailyRefreshDue(false, true, after, after))
    }

    @Test fun timeZoneAndDaylightSavingChangesStillUseCalendarDays() {
        val instant = Instant.parse("2026-10-06T16:30:00Z").toEpochMilli()
        assertEquals("2026-10-06", homeRefreshDay(instant, TimeZone.getTimeZone("UTC")))
        assertEquals("2026-10-07", homeRefreshDay(instant, TimeZone.getTimeZone("Asia/Singapore")))
        val zone = TimeZone.getTimeZone("America/New_York")
        val before = homeRefreshDay(Instant.parse("2026-11-01T04:30:00Z").toEpochMilli(), zone)
        val after = homeRefreshDay(Instant.parse("2026-11-02T05:00:00Z").toEpochMilli(), zone)
        assertTrue(homeDailyRefreshDue(false, true, before, after))
    }

    @Test fun manualRefreshBypassesTodayButMissingContentAlwaysLoads() {
        assertTrue(homeDailyRefreshDue(true, true, "2026-10-07", "2026-10-07"))
        assertTrue(homeDailyRefreshDue(false, false, "2026-10-07", "2026-10-07"))
        assertTrue(homeDailyRefreshDue(false, true, null, "2026-10-07"))
    }

    @Test fun restoredContentKeepsItsSuccessfulDateRatherThanReadTime() {
        val old = HomeDailySnapshot("2026-10-06", "缓存内容")
        val restored = decodeHomeDailySnapshot(encodeHomeDailySnapshot(old))!!
        assertEquals(old, restored)
        assertTrue(homeDailyRefreshDue(false, true, restored.refreshedDay, "2026-10-07"))
        val today = restored.copy(refreshedDay = "2026-10-07")
        assertFalse(homeDailyRefreshDue(false, true,
            decodeHomeDailySnapshot(encodeHomeDailySnapshot(today))!!.refreshedDay, "2026-10-07"))
        assertNull(decodeHomeDailySnapshot("损坏的缓存"))
    }

    @Test fun homepageRefreshReachesAllThreeSectionsForManualAndAutomaticTriggers() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val refresh = QqHomeRefresh({ calls += "daily" to it }, { calls += "similar" to it }, { calls += "feed" to it })
        refresh.refresh(true)
        assertEquals(listOf("daily" to true, "similar" to true, "feed" to true), calls)
        calls.clear()
        refresh.refresh(false)
        assertEquals(listOf("daily" to false, "similar" to false, "feed" to false), calls)
    }

    @Test fun dailyTracksRestoreNextBatchAndMetadataWithoutPlaybackTickets() {
        val original = RecommendedTracksSnapshot(listOf(track("qq-daily")), 4, "2026-10-07")
        val restored = decodeRecommendedTracksSnapshot(encodeRecommendedTracksSnapshot(original))!!
        assertEquals(original.nextPage, restored.nextPage)
        assertEquals(original.refreshedDay, restored.refreshedDay)
        assertEquals(original.tracks.single().artworkUrl, restored.tracks.single().artworkUrl)
        assertEquals(original.tracks.single().id, restored.tracks.single().id)
        assertEquals("", restored.tracks.single().previewUrl)
        assertNull(decodeRecommendedTracksSnapshot(encodeRecommendedTracksSnapshot(original.copy(tracks = emptyList()))))
    }

    @Test fun similarSnapshotRestoresTheSameSeedsTitlesAndSongOrder() {
        val pages = listOf(QqSimilarRecommendation(track("qq-seed"), "听过这首歌也会喜欢",
            listOf(QqSimilarSong(track("qq-one")), QqSimilarSong(track("qq-two")))))
        val restored = decodeQqSimilarRecommendationSnapshot(
            encodeQqSimilarRecommendationSnapshot(QqSimilarRecommendationSnapshot(pages, "2026-10-07")))!!
        assertEquals("2026-10-07", restored.refreshedDay)
        assertEquals(pages.single().title, restored.pages.single().title)
        assertEquals("qq-seed", restored.pages.single().baseTrack.id)
        assertEquals(listOf("qq-one", "qq-two"), restored.pages.single().songs.map { it.track.id })
        assertNull(decodeQqSimilarRecommendationSnapshot(HomeDailySnapshot("2026-10-07", "[]")))
    }

    private fun track(id: String) = MusicTrack(id, MusicSource.QQ, "测试歌曲", "歌手", "专辑", 180_000,
        0L, 0L, "测", "临时播放票据", artworkUrl = "https://example.com/cover.jpg", catalogId = "123")
}
