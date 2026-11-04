package com.musicone

import com.musicone.source.sdk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class MainSourceHomePagerCompatibilityTest {
    @Test fun providerBufferTriggersContinuationWithoutDelayingTheFirstPage() = runBlocking {
        var requests = 0
        val pager = MainSourceHomePager(load = { request ->
            requests++
            if (request.cursor == null) SourcePage(listOf(shelf("one", "first")), "buffered")
            else SourcePage(listOf(SourceHomeSection("songs", "后续歌曲", tracks = listOf(details("deferred")))), "remote")
        }, presentation = ::semantics, buffered = { it == "buffered" })

        val first = pager.next(emptyList(), 6, true)
        assertEquals(1, requests)
        assertEquals(1, first.visible.size)
        assertTrue(first.remaining.isEmpty())
        assertTrue(pager.hasBufferedItems)

        val second = pager.next(first.visible, 9, false)
        assertEquals(2, requests)
        assertEquals("deferred", (second.visible.single() as MainSourceHomeCard.Song).track.remoteKey)
        assertFalse(pager.hasBufferedItems)
        assertFalse(pager.finished)
    }

    @Test fun oldProviderWithoutBufferCapabilityDoesNotAutoloadRemotePage() = runBlocking {
        var requests = 0
        val pager = MainSourceHomePager(load = {
            requests++
            SourcePage(listOf(shelf("one", "first")), "remote")
        }, presentation = ::semantics)
        pager.next(emptyList(), 6, true)
        assertFalse(pager.hasBufferedItems)
        assertEquals(1, requests)
    }

    @Test fun hostPreservesTheProviderBatchOrder() = runBlocking {
        val sections = listOf("a", "b", "c").map { id -> SourceHomeSection(id, id, tracks = listOf(details(id))) } +
            listOf("one", "two").map { id -> SourceHomeSection(id, id, collections = listOf(SourceCollection(
                TrackIdentity(MusicSource.QQ.id, null, id), id, id,
            ))) }
        val pager = MainSourceHomePager(load = { SourcePage(sections, null) })
        val cards = pager.next(emptyList(), 6, true).visible
        assertEquals(listOf("a", "b", "c"), cards.take(3).map { (it as MainSourceHomeCard.Song).track.remoteKey })
        assertTrue(cards.drop(3).all { it is MainSourceHomeCard.Playlist })
    }

    @Test fun refreshDoesNotFilterTheSameRecommendationsAgainstOldScreen() = runBlocking {
        val pager = MainSourceHomePager(load = { SourcePage(listOf(shelf("one", "a", "b", "c")), null) }, presentation = ::semantics)
        val old = pager.next(emptyList(), 6, false).visible
        assertEquals(old.map { it.key }, pager.next(old, 6, true).visible.map { it.key })
    }

    @Test fun skipsRepeatedPagesThenAppendsNewHorizontalPageToExistingShelf() = runBlocking {
        var calls = 0
        val pager = MainSourceHomePager(load = {
            calls++
            when (calls) {
                1 -> SourcePage(listOf(shelf("one", "a", "b", "c")), "second")
                2 -> SourcePage(listOf(shelf("one", "a", "b", "c")), "third")
                else -> SourcePage(listOf(shelf("one", "d", "e", "f")), null)
            }
        }, presentation = ::semantics)
        val old = pager.next(emptyList(), 6, false).visible
        val fresh = pager.next(old, 6, false).visible
        val merged = appendMainSourceHomeDisplayCards(old, fresh, false).single() as MainSourceHomeCard.SongShelf
        assertEquals(3, calls)
        assertEquals(2, merged.pages.size)
        assertEquals("song-shelf:one:官方推荐", merged.key)
        assertEquals(listOf("d", "e", "f"), merged.pages.last().map { it.track.remoteKey })
        assertTrue(pager.finished)
    }

    @Test fun providerGroupLimitKeepsThreeOfficialShelvesAndLeavesIndependentGroupsAlone() = runBlocking {
        val pager = MainSourceHomePager(load = {
            SourcePage((1..4).map { shelf(it.toString(), "a$it") }, null)
        }, presentation = ::semantics)
        val official = pager.next(emptyList(), 10, false).visible
        assertEquals(3, official.size)
        val independent = MainSourceHomeCard.SongGroup("MusicOne相似推荐", listOf(track("independent")))
        assertEquals(4, appendMainSourceHomeDisplayCards(official, listOf(independent), false).size)
    }

    @Test fun legacyDailyFallbackRejectsOrdinaryTrackSectionsAndPollutedSnapshots() {
        val ordinary = SourceHomeSection("feed", "推荐", tracks = listOf(details("ordinary")))
        assertTrue(legacyDailyRecommendationTracks(listOf(ordinary)).isEmpty())
        val daily = ordinary.copy(id = "daily", tracks = listOf(details("daily")),
            payload = SourcePayload("musicone.home-section", 1, """{"layout":"daily"}"""))
        assertEquals(listOf("daily"), legacyDailyRecommendationTracks(listOf(ordinary, daily)).map { it.stableId })
        val complete = daily.copy(tracks = (1..30).map { details("daily-$it") })
        assertEquals(30, legacyDailyRecommendationTracks(listOf(ordinary, complete)).size)
        val contaminated = encodeRecommendedTracksSnapshot(RecommendedTracksSnapshot(listOf(track("ordinary-feed")), 4, "2026-10-22"))
        val legacyPayload = JSONObject(contaminated.payload).apply { remove("kind"); remove("version"); put("nextPage", 4) }
        assertNull(decodeRecommendedTracksSnapshot(contaminated.copy(payload = legacyPayload.toString())))
    }

    private fun semantics(section: SourceHomeSection) = SourceHomePresentation(section.id, mergePages = true,
        recommendation = true, groupId = "official", groupLimit = 3)
    private fun shelf(id: String, vararg tracks: String) = SourceHomeSection("song-shelf:$id:官方推荐", "官方推荐",
        tracks = tracks.map(::details), payload = SourcePayload("musicone.home-section", 1, """{"layout":"track-shelf"}"""))
    private fun details(id: String) = SourceTrackDetails(SourceTrack(TrackIdentity(MusicSource.QQ.id, null, id), id), id)
    private fun track(id: String) = MusicTrack(id, MusicSource.QQ, id, "歌手", "", 0, 0, 0, "", "")
}
