package com.musicone.source.runtime

import androidx.compose.runtime.Composable
import com.musicone.source.sdk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SourceEntityArtworkLeaseTest {
    private val reference = SourceEntityRef("singer", "歌手", "artist")

    @Test fun lightweightArtworkSurvivesGuardAndRemovalRejectsAccess() = runBlocking {
        var active = true
        var calls = 0
        val service = object : EmptyEntities(), SourceEntityArtwork {
            override suspend fun artwork(reference: SourceEntityRef): SourceArtwork {
                calls++
                return SourceArtwork("https://example.com/light.jpg")
            }
            override suspend fun entity(reference: SourceEntityRef): SourceEntity = error("不应读取完整歌手资料")
        }
        val lease = SourceOperationLease { check(active) }
        try {
            val guarded = SourceGuardedEntry(entry(service), lease).entities!!
            assertEquals("https://example.com/light.jpg", guarded.artwork(reference).url)
            active = false
            assertTrue(runCatching { guarded.artwork(reference) }.exceptionOrNull() is IllegalStateException)
            assertEquals(1, calls)
        } finally { lease.close() }
    }

    @Test fun oldEntitiesKeepTheirExistingArtworkFallback() = runBlocking {
        val service = object : EmptyEntities() {
            override suspend fun entity(reference: SourceEntityRef) = SourceEntity(reference,
                artwork = SourceArtwork("https://example.com/old.jpg"))
        }
        val lease = SourceOperationLease { }
        try {
            assertEquals("https://example.com/old.jpg", SourceGuardedEntry(entry(service), lease).entities!!.artwork(reference).url)
        } finally { lease.close() }
    }

    private fun entry(service: SourceEntities) = object : SourceEntry {
        override val descriptor = SourceDescriptor("org.example.artwork", "头像测试", SourceKind.MAIN, 1, "1", emptySet())
        override val implementedCapabilities = emptySet<SourceCapability>()
        override val entities = service
        override fun initialize(environment: SourceEnvironment) {}
        override fun close() {}
        @Composable override fun Content(route: SourceRoute) {}
    }

    private abstract class EmptyEntities : SourceEntities {
        override val kinds = setOf("artist")
        override suspend fun tracks(reference: SourceEntityRef, request: SourceCursorRequest) = SourcePage<SourceTrackDetails>(emptyList(), null)
        override suspend fun collections(reference: SourceEntityRef, request: SourceCursorRequest) = SourcePage<SourceCollection>(emptyList(), null)
    }
}
