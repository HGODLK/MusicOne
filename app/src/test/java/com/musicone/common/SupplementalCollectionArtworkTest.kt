package com.musicone

import com.musicone.source.sdk.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SupplementalCollectionArtworkTest {
    private val folder = SourceRemoteCollection(TrackIdentity("source", "account", "folder"), "收藏夹",
        artwork = SourceArtwork("old.jpg"))

    @Test fun visibleRefreshKeepsOldUntilReadyAndReloadsEvenTheSameUrl() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        var calls = 0
        val loader = SupplementalCollectionArtworkLoader(object : SourceRemoteCollectionArtwork {
            override suspend fun artwork(collection: SourceRemoteCollection, policy: SourceFetchPolicy): SourceArtwork {
                assertEquals(SourceFetchPolicy.REFRESH, policy)
                calls++; ready.await(); return collection.artwork
            }
        })
        var state = SupplementalState(collections = listOf(folder), directoryArtworkRevision = 1)
        val job = launch { state = state.withCollectionArtwork(folder.identity, loader.load(folder, 0, 1)!!, 1) }
        yield()
        assertEquals("old.jpg", state.collections.single().artwork.url)
        assertTrue(state.artworkVersions.isEmpty())
        ready.complete(Unit); job.join()
        assertEquals(1L, state.artworkVersions[folder.identity])
        loader.load(folder, 0, 1)
        assertEquals(1, calls)
        loader.load(folder, 0, 2)
        assertEquals(2, calls)
    }

    @Test fun failureAndBlankResultLeaveTheOldCoverAndVersion() = runBlocking {
        for (fails in listOf(true, false)) {
            val loader = SupplementalCollectionArtworkLoader(object : SourceRemoteCollectionArtwork {
                override suspend fun artwork(collection: SourceRemoteCollection, policy: SourceFetchPolicy): SourceArtwork {
                    if (fails) error("封面读取失败")
                    return SourceArtwork()
                }
            })
            assertNull(loader.load(folder, 0, 1))
        }
        val state = SupplementalState(collections = listOf(folder), directoryArtworkRevision = 2)
        assertEquals(state, state.withCollectionArtwork(folder.identity, SourceArtwork(), 2))
        assertEquals(state, state.withCollectionArtwork(folder.identity, SourceArtwork("stale.jpg"), 1))
    }

    @Test fun leavingTheVisibleAreaCancelsAndAllowsReentryToRetry() = runBlocking {
        var calls = 0
        val loader = SupplementalCollectionArtworkLoader(object : SourceRemoteCollectionArtwork {
            override suspend fun artwork(collection: SourceRemoteCollection, policy: SourceFetchPolicy): SourceArtwork {
                if (++calls == 1) awaitCancellation()
                return SourceArtwork("new.jpg")
            }
        })
        val first = launch { loader.load(folder, 0, 1) }
        yield(); first.cancelAndJoin()
        assertEquals("new.jpg", loader.load(folder, 0, 1)?.url)
        assertEquals(2, calls)
        assertEquals("new.jpg", loader.load(folder, 1, 1)?.url)
        assertEquals(3, calls)
    }
}
