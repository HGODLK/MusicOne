package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteButtonBehaviorTest {
    private val webdav = MusicSource.fromId("com.musicone.source.webdav")

    private fun track(id: String, source: MusicSource, aliases: List<String> = emptyList()) = MusicTrack(
        id = id,
        source = source,
        title = id,
        artists = "歌手",
        album = "专辑",
        durationMs = 180_000L,
        artworkStart = 0xFF112233,
        artworkEnd = 0xFF445566,
        artworkMark = "歌",
        previewUrl = "",
        identityAliases = aliases,
    )

    @Test fun storedValuesRoundTripAndFallbackToSourceOnly() {
        FavoriteButtonBehavior.entries.forEach { behavior ->
            assertEquals(behavior, FavoriteButtonBehavior.fromStored(behavior.storedValue))
        }
        assertEquals(FavoriteButtonBehavior.SOURCE_ONLY, FavoriteButtonBehavior.fromStored(null))
        assertEquals(FavoriteButtonBehavior.SOURCE_ONLY, FavoriteButtonBehavior.fromStored("unknown"))
    }

    @Test fun displayedIdsFollowBehaviorAndFallbackSources() {
        val qqInUnified = track("qq-unified", MusicSource.QQ, aliases = listOf("qq-alias"))
        val webdavInUnified = track("webdav-1", webdav)
        val localInUnified = track("local-1", MusicSource.LOCAL)
        val unified = listOf(qqInUnified, webdavInUnified, localInUnified)
        val remote = setOf("qq-remote")
        val supportsRemote: (MusicSource) -> Boolean = { it == MusicSource.QQ }

        val sourceOnly = displayedFavoriteIds(remote, unified, FavoriteButtonBehavior.SOURCE_ONLY, supportsRemote)
        assertTrue("qq-remote" in sourceOnly)
        assertTrue("webdav-1" in sourceOnly)
        assertTrue("local-1" in sourceOnly)
        assertFalse("qq-unified" in sourceOnly)

        val both = displayedFavoriteIds(remote, unified, FavoriteButtonBehavior.BOTH, supportsRemote)
        assertEquals(setOf("qq-remote", "qq-unified", "qq-alias", "webdav-1", "local-1"), both)

        val unifiedOnly = displayedFavoriteIds(remote, unified, FavoriteButtonBehavior.UNIFIED_ONLY, supportsRemote)
        assertEquals(setOf("qq-unified", "qq-alias", "webdav-1", "local-1"), unifiedOnly)
    }

    @Test fun unsupportedSourcesAlwaysRouteToUnifiedRegardlessOfBehavior() {
        val song = track("webdav-1", webdav)
        FavoriteButtonBehavior.entries.forEach { behavior ->
            val addPlan = planFavoriteToggle(song, behavior, supportsRemote = false, remoteIds = emptySet(), unifiedIds = emptySet())
            assertNull(addPlan.remoteTarget)
            assertEquals(true, addPlan.unifiedTarget)

            val removePlan = planFavoriteToggle(song, behavior, supportsRemote = false, remoteIds = emptySet(), unifiedIds = setOf("webdav-1"))
            assertNull(removePlan.remoteTarget)
            assertEquals(false, removePlan.unifiedTarget)
        }
    }

    @Test fun supportedSourceHonorsAllThreeBehaviors() {
        val song = track("qq-1", MusicSource.QQ)

        val sourceAdd = planFavoriteToggle(song, FavoriteButtonBehavior.SOURCE_ONLY, true, emptySet(), setOf("qq-1"))
        assertEquals(true, sourceAdd.remoteTarget)
        assertNull(sourceAdd.unifiedTarget)

        val unifiedAdd = planFavoriteToggle(song, FavoriteButtonBehavior.UNIFIED_ONLY, true, setOf("qq-1"), emptySet())
        assertNull(unifiedAdd.remoteTarget)
        assertEquals(true, unifiedAdd.unifiedTarget)

        val bothAdd = planFavoriteToggle(song, FavoriteButtonBehavior.BOTH, true, emptySet(), emptySet())
        assertEquals(true, bothAdd.remoteTarget)
        assertEquals(true, bothAdd.unifiedTarget)

        val bothRemoveAll = planFavoriteToggle(song, FavoriteButtonBehavior.BOTH, true, setOf("qq-1"), setOf("qq-1"))
        assertEquals(false, bothRemoveAll.remoteTarget)
        assertEquals(false, bothRemoveAll.unifiedTarget)

        val bothRemoveRemoteOnly = planFavoriteToggle(song, FavoriteButtonBehavior.BOTH, true, setOf("qq-1"), emptySet())
        assertEquals(false, bothRemoveRemoteOnly.remoteTarget)
        assertNull(bothRemoveRemoteOnly.unifiedTarget)

        val bothRemoveUnifiedOnly = planFavoriteToggle(song, FavoriteButtonBehavior.BOTH, true, emptySet(), setOf("qq-1"))
        assertNull(bothRemoveUnifiedOnly.remoteTarget)
        assertEquals(false, bothRemoveUnifiedOnly.unifiedTarget)
    }
}
