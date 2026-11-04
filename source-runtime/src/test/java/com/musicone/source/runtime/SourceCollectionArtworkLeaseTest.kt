package com.musicone.source.runtime

import androidx.compose.runtime.Composable
import com.musicone.source.sdk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SourceCollectionArtworkLeaseTest {
    private val folder = SourceRemoteCollection(TrackIdentity("source", "account", "folder"), "收藏夹")

    @Test fun optionalArtworkIsForwardedAndRemovalRejectsRequests() = runBlocking {
        var active = true
        var calls = 0
        val service = object : EmptyCollections(), SourceRemoteCollectionArtwork {
            override suspend fun artwork(collection: SourceRemoteCollection, policy: SourceFetchPolicy): SourceArtwork {
                calls++; return SourceArtwork("new.jpg")
            }
        }
        val lease = SourceOperationLease { check(active) }
        try {
            val guarded = SourceGuardedEntry(entry(service), lease).remoteCollections as SourceRemoteCollectionArtwork
            assertEquals("new.jpg", guarded.artwork(folder, SourceFetchPolicy.REFRESH).url)
            active = false
            assertTrue(runCatching { guarded.artwork(folder, SourceFetchPolicy.REFRESH) }.exceptionOrNull() is IllegalStateException)
            assertEquals(1, calls)
        } finally { lease.close() }
    }

    @Test fun oldPackagesDoNotAdvertiseTheOptionalArtworkCapability() {
        val lease = SourceOperationLease { }
        try {
            assertFalse(SourceGuardedEntry(entry(object : EmptyCollections() {}), lease).remoteCollections is SourceRemoteCollectionArtwork)
        } finally { lease.close() }
    }

    private fun entry(service: SourceRemoteCollections) = object : SourceEntry {
        override val descriptor = SourceDescriptor("org.example.artwork", "封面测试", SourceKind.SUPPLEMENTAL, 1, "1", emptySet())
        override val implementedCapabilities = emptySet<SourceCapability>()
        override val remoteCollections = service
        override fun initialize(environment: SourceEnvironment) {}
        override fun close() {}
        @Composable override fun Content(route: SourceRoute) {}
    }
    private abstract class EmptyCollections : SourceRemoteCollections {
        override suspend fun collections(request: SourceCursorRequest) = SourcePage<SourceRemoteCollection>(emptyList(), null)
        override suspend fun contents(collection: SourceRemoteCollection, query: String?, request: SourceCursorRequest) = SourcePage<SourceContent>(emptyList(), null)
    }
}
