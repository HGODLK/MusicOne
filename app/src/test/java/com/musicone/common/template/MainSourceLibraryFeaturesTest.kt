package com.musicone

import com.musicone.source.sdk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MainSourceLibraryFeaturesTest {
    private val playlist = MusicPlaylist("public", MusicSource.KUGOU, "歌单", "", "", 0, 0L, 0L, "", emptyList())
    private val collection = SourceCollection(TrackIdentity("test", "42", "list"), "list", "歌单")
    private val policy = object : SourceFavorites, SourceCollectionFavoritePolicy {
        override suspend fun isTrackFavorite(track: SourceTrackDetails) = false
        override suspend fun setTrackFavorite(track: SourceTrackDetails, favorite: Boolean) {}
        override fun canFavorite(collection: SourceCollection) = collection.stableId != "recent"
        override fun favoriteIdentityKeys(collection: SourceCollection) = setOf("origin:1", collection.stableId)
    }

    @Test fun aliasesMatchPublicAndSavedCopiesWithoutChangingStableIdsOrOtherSources() {
        assertTrue(sameSourceCollection(playlist, playlist.copy(id = "saved"), policy, "42"))
        assertFalse(sameSourceCollection(playlist, playlist.copy(source = MusicSource.QQ), policy, "42"))
        assertFalse(sameSourceCollection(playlist, playlist.copy(id = "saved"), null, "42"))
        assertTrue(sameSourceCollection(playlist, playlist, null, "42"))
    }

    @Test fun readOnlyCollectionPolicyDoesNotNeedEphemeralPaginationFlag() {
        val recent = playlist.copy(id = "recent")
        assertTrue(recent.canCollectAsPlaylist())
        assertFalse(recent.canCollectWith(policy, "42"))
        assertTrue(playlist.canCollectWith(policy, "42"))
        assertTrue(recent.canCollectWith(null, "42"))
    }

    @Test fun remoteOrderReadsAll2507RowsAndUsesRefreshOnlyOnFirstPage() = runBlocking {
        val requests = mutableListOf<SourceCursorRequest>()
        val service = ordering { request ->
            requests += request
            val start = request.cursor?.toInt() ?: 0
            val end = minOf(start + 100, 2507)
            SourcePage((start until end).map(::track), end.takeIf { it < 2507 }?.toString())
        }
        val result = loadRemoteCollectionOrder(service, collection) {}
        assertEquals((0 until 2507).map(Int::toString), result.map { it.stableId })
        assertEquals(SourceFetchPolicy.REFRESH, requests.first().policy)
        assertTrue(requests.drop(1).all { it.policy == SourceFetchPolicy.CACHE_FIRST })
    }

    @Test fun repeatedCursorAndDuplicateRowsNeverBecomeSaveableOrder() = runBlocking {
        val cycle = ordering { SourcePage(listOf(track(1)), "same") }
        assertTrue(runCatching { loadRemoteCollectionOrder(cycle, collection) {} }.exceptionOrNull() is IllegalStateException)
        val duplicate = ordering { SourcePage(listOf(track(1), track(1)), null) }
        assertTrue(runCatching { loadRemoteCollectionOrder(duplicate, collection) {} }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun accountChangeBeforeSecondPageRejectsPartialOrder() = runBlocking {
        var current = true
        val service = ordering { current = false; SourcePage(listOf(track(1)), "next") }
        assertTrue(runCatching {
            loadRemoteCollectionOrder(service, collection) { if (!current) throw CancellationException("换号") }
        }.exceptionOrNull() is CancellationException)
    }

    private fun track(id: Int) = SourceTrackDetails(SourceTrack(TrackIdentity("test", "42", "$id"), "歌曲$id"), "$id")
    private fun ordering(load: (SourceCursorRequest) -> SourcePage<SourceTrackDetails>) = object : SourceCollectionRemoteOrder {
        override fun supportsRemoteOrder(collection: SourceCollection) = true
        override suspend fun orderedTracks(collection: SourceCollection, request: SourceCursorRequest) = load(request)
    }
}
