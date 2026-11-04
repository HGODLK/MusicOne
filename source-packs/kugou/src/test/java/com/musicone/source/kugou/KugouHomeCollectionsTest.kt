package com.musicone.source.kugou

import com.musicone.source.sdk.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KugouHomeCollectionsTest {
    private class Delegate : SourceCollections, SourceCollectionCachePolicy, SourceCollectionReturnBehavior {
        var details = 0
        var tracks = 0
        override val refreshLibraryAfterAnyReturn = true
        override val librarySections = setOf(SourceLibrarySection.CREATED_COLLECTIONS)
        override fun includesInCacheLibrary(collection: SourceCollection) = collection.owned
        override suspend fun detail(collection: SourceCollection): SourceCollection { details++; return collection }
        override suspend fun tracks(collection: SourceCollection, request: SourceCursorRequest): SourcePage<SourceTrackDetails> {
            tracks++; return SourcePage(emptyList(), request.cursor)
        }
    }

    private fun track(n: Int, owner: String? = "42") = SourceTrackDetails(
        SourceTrack(TrackIdentity(KUGOU_SOURCE_ID, owner, "HASH$n"), "歌曲$n", listOf("歌手"), "track$n.jpg",
            mapOf("catalogId" to "$n", "mediaId" to "9", "qualityIds" to "{original:$n}")), "legacy-stable-$n", "专辑", n.toLong(),
        SourceArtwork("cover$n.jpg", 11, 22, "封"), listOf(SourceEntityRef("8", "歌手", "artist", SourcePayload("artist", 1, "raw"))),
        SourceEntityRef("9", "专辑", "album"), TrackAccessInfo(TrackAccessBadge.PURCHASE, true, false, "真实付费", SourceTrackBadge("购买")),
        listOf(SourceAudioQuality("LOSSLESS", "无损", "audio/flac", false, "版权", SourcePayload("quality", 3, "HASH-FLAC$n"))),
        payload = SourcePayload("kugou.track", 2, "{original:$n}"), artistsText = "原歌手文本", previewUrl = "preview$n", identityAliases = listOf("alias$n"))

    @Test fun fullThousandAndSevenTracksRemainOrderedAndUnchangedBeyondHostLimit() = runBlocking {
        val delegate = Delegate()
        val wrapper = KugouHomeCollections({ "42" }, delegate)
        val original = List(1007) { track(it) }
        val initial = wrapper.collection("recent", null, original)
        assertEquals("最近播放", initial.title)
        assertEquals("1007 首歌曲", initial.subtitle)
        assertEquals("kugou-home-view:recent:42:", initial.stableId)
        assertFalse(initial.owned)
        assertFalse(initial.accountFavorites)
        assertFalse(JSONObject(initial.payload.encoded).has("templateEphemeral"))
        val detail = wrapper.detail(initial)
        assertFalse(JSONObject(detail.payload.encoded).has("templateEphemeral"))
        assertEquals(1007, detail.count)
        assertEquals(original.first().artwork, detail.artwork)
        val received = mutableListOf<SourceTrackDetails>()
        var cursor: String? = null
        do {
            val page = wrapper.tracks(detail, SourceCursorRequest(cursor, 100))
            assertTrue(page.items.size <= 100)
            received += page.items
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(original, received)
        assertFalse(initial.payload.encoded.contains("kugouHomeTracks"))
        assertTrue(initial.payload.encoded.length < 512)
        assertEquals(0, delegate.details)
        assertEquals(0, delegate.tracks)
        assertFalse(wrapper.includesInCacheLibrary(detail))
        rejected { wrapper.tracks(detail, SourceCursorRequest("100", 100)) }
    }

    @Test fun dailyKeepsTheCompleteListAndEphemeralTransitionContract() = runBlocking {
        val wrapper = KugouHomeCollections({ "42" }, Delegate())
        val original = List(137) { track(it) }
        val initial = wrapper.collection("daily", "20261023", original)
        val detail = wrapper.detail(initial)
        assertEquals("true", JSONObject(detail.payload.encoded).getString("templateEphemeral"))
        assertEquals(original, wrapper.tracks(detail, SourceCursorRequest(limit = 100)).items)
    }

    @Test fun accountSwitchAndHostRewrittenIdentityCannotReuseOldPayload() = runBlocking {
        var owner = "42"
        val wrapper = KugouHomeCollections({ owner }, Delegate())
        val initial = wrapper.collection("daily", "20261023", listOf(track(1)))
        owner = "77"
        rejected { wrapper.detail(initial) }
        val rewritten = initial.copy(identity = initial.identity.copy(accountId = "77"))
        rejected { wrapper.detail(rewritten) }
        rejected { wrapper.tracks(rewritten, SourceCursorRequest()) }
    }

    @Test fun everyTrackSourceAndAccountAreValidatedAgainWhenReadingPayload() = runBlocking {
        val wrapper = KugouHomeCollections({ "42" }, Delegate())
        val initial = wrapper.collection("daily", "20261023", listOf(track(1)))
        listOf(track(1, "77"), track(1).let { it.copy(track = it.track.copy(identity = it.track.identity.copy(sourceId = "other.source"))) }).forEach { foreign ->
            val payload = JSONObject(initial.payload.encoded).put("kugouHomeTracks", JSONArray().put(writeTrack(foreign)).toString())
            val collection = initial.copy(payload = initial.payload.copy(encoded = payload.toString()))
            rejected { wrapper.detail(collection) }
            rejected { wrapper.tracks(collection, SourceCursorRequest()) }
        }
    }

    @Test fun ordinaryCollectionsAndPoliciesKeepDelegatingWithoutVirtualDirectoryEntries() = runBlocking {
        val delegate = Delegate()
        val wrapper = KugouHomeCollections({ "42" }, delegate)
        val ordinary = SourceCollection(TrackIdentity(KUGOU_SOURCE_ID, "42", "cloudlist:9"), "kugou-cloudlist:9", "原云歌单", owned = true)
        assertSame(ordinary, wrapper.detail(ordinary))
        assertEquals("original-cursor", wrapper.tracks(ordinary, SourceCursorRequest("original-cursor")).nextCursor)
        assertEquals(1, delegate.details)
        assertEquals(1, delegate.tracks)
        assertTrue(wrapper.includesInCacheLibrary(ordinary))
        assertTrue(wrapper.refreshLibraryAfterAnyReturn)
        assertEquals(delegate.librarySections, wrapper.librarySections)
        assertFalse(ordinary.isKugouHomeCollection())
    }

    @Test fun emptySnapshotUsesRealZeroAndMalformedViewCannotReachDelegate() = runBlocking {
        val delegate = Delegate()
        val wrapper = KugouHomeCollections({ "42" }, delegate)
        val initial = wrapper.collection("recent", null, emptyList())
        assertEquals(0, wrapper.detail(initial).count)
        assertTrue(wrapper.tracks(initial, SourceCursorRequest()).items.isEmpty())
        val malformed = initial.copy(payload = SourcePayload("wrong", 1, "{}"))
        rejected { wrapper.detail(malformed) }
        rejected { wrapper.tracks(malformed, SourceCursorRequest()) }
        assertEquals(0, delegate.details)
        assertEquals(0, delegate.tracks)
    }

    @Test fun openRecentSnapshotSurvivesHomeReplacementAndCursorsCannotCrossSnapshots() = runBlocking {
        val wrapper = KugouHomeCollections({ "42" }, Delegate())
        val first = wrapper.collection("recent", null, List(5) { track(it) })
        wrapper.retain(first)
        val cursor = requireNotNull(wrapper.tracks(first, SourceCursorRequest(limit = 2)).nextCursor)
        val replacement = wrapper.collection("recent", null, listOf(track(99)))
        assertEquals(5, wrapper.detail(first).count)
        assertEquals(listOf(track(2), track(3)), wrapper.tracks(first, SourceCursorRequest(cursor, 2)).items)
        rejected { wrapper.tracks(replacement, SourceCursorRequest(cursor, 2)) }
        wrapper.release(first)
        wrapper.collection("recent", null, listOf(track(100)))
        try { wrapper.detail(first); fail("已释放快照应失效") }
        catch (_: IllegalStateException) { }
    }

    @Test fun clearingMemoryInvalidatesEvenRetainedViewsAndAllowsFreshViews() = runBlocking {
        val wrapper = KugouHomeCollections({ "42" }, Delegate())
        val previous = wrapper.collection("recent", null, List(2507) { track(it) })
        wrapper.retain(previous)
        wrapper.clearMemory()
        try { wrapper.detail(previous); fail("换号清理后不能继续读取已保留的旧快照") }
        catch (_: IllegalStateException) { }
        val fresh = wrapper.collection("recent", null, listOf(track(99)))
        assertEquals(listOf(track(99)), wrapper.tracks(fresh, SourceCursorRequest()).items)
    }

    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("应拒绝错误账号、身份或分页") }
        catch (_: IllegalArgumentException) { }
    }
}
