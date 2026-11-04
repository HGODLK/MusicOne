package com.musicone

import com.musicone.source.sdk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MainSourceLibraryRequestsTest {
    @Test
    fun initialReadAndUnchangedReturnKeepCachePolicyThroughEveryDirectoryPage() = runBlocking {
        val service = RecordingCollections()
        val result = service.loadMainSourceLibrary(SourceLibrarySection.CREATED_COLLECTIONS,
            libraryReturnFetchPolicy(contentChanged = false))
        assertEquals(listOf("first", "second"), result.map { it.stableId })
        assertEquals(listOf(null, "next"), service.directoryRequests.map { it.cursor })
        assertEquals(listOf(SourceFetchPolicy.CACHE_FIRST, SourceFetchPolicy.CACHE_FIRST),
            service.directoryRequests.map { it.policy })
    }

    @Test
    fun changedReturnRefreshesBothDirectoryAndFavoriteTracks() = runBlocking {
        val service = RecordingCollections()
        val policy = libraryReturnFetchPolicy(contentChanged = true)
        service.loadMainSourceLibrary(SourceLibrarySection.SAVED_COLLECTIONS, policy)
        service.loadMainSourceFavorites(policy)
        assertEquals(listOf(SourceFetchPolicy.REFRESH, SourceFetchPolicy.REFRESH, SourceFetchPolicy.REFRESH),
            service.directoryRequests.map { it.policy })
        assertEquals(SourceFetchPolicy.REFRESH, service.trackRequests.single().policy)
        assertEquals(PLAYLIST_TRACK_PAGE_SIZE, service.trackRequests.single().limit)
    }

    @Test
    fun cacheFavoriteReadKeepsCachePolicyForTrackRequest() = runBlocking {
        val service = RecordingCollections()
        service.loadMainSourceFavorites(SourceFetchPolicy.CACHE_FIRST)
        assertEquals(SourceFetchPolicy.CACHE_FIRST, service.directoryRequests.single().policy)
        assertEquals(SourceFetchPolicy.CACHE_FIRST, service.trackRequests.single().policy)
    }

    private class RecordingCollections : SourceCollections {
        val directoryRequests = mutableListOf<SourceCursorRequest>()
        val trackRequests = mutableListOf<SourceCursorRequest>()
        override val librarySections = SourceLibrarySection.entries.toSet()
        override suspend fun library(section: SourceLibrarySection, request: SourceCursorRequest): SourcePage<SourceCollection> {
            directoryRequests += request
            val collection = SourceCollection(TrackIdentity("test", null, "collection"),
                if (request.cursor == null) "first" else "second", "歌单")
            return SourcePage(listOf(collection),
                if (section != SourceLibrarySection.FAVORITE_TRACKS && request.cursor == null) "next" else null)
        }
        override suspend fun detail(collection: SourceCollection) = collection
        override suspend fun tracks(collection: SourceCollection, request: SourceCursorRequest): SourcePage<SourceTrackDetails> {
            trackRequests += request
            return SourcePage(emptyList(), null)
        }
    }
}
