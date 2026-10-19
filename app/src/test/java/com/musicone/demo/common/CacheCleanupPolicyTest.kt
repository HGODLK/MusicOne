package com.musicone.demo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CacheCleanupPolicyTest {
    @Test fun playlistUnionRetainsEveryVariantAndUnknownResources() {
        val resources = linkedMapOf("qq-flac" to "QQ|1", "qq-mp3" to "QQ|1",
            "netease" to "NETEASE|1", "outside" to "QQ|2", "legacy" to null)
        assertEquals(listOf("outside"), outsidePlaylistCacheKeys(resources, setOf("QQ|1", "NETEASE|1")))
        assertEquals(listOf("qq-flac", "qq-mp3", "netease", "outside"), outsidePlaylistCacheKeys(resources, emptySet()))
    }

    @Test fun cacheSelectionSeparatesProvidersWithTheSameSongId() = runBlocking {
        val qq = MusicTrack("1", MusicSource.QQ, "歌曲", "", "", 0, 0, 0, "", "")
        val netease = qq.copy(source = MusicSource.NETEASE)
        val selection = PlaylistMultiSelection { it.cacheIdentity() }
        selection.update(listOf(qq, netease))
        selection.selectAll { listOf(qq, netease) }
        assertEquals(2, selection.ids.size)
        selection.toggle(selection.key(qq))
        assertEquals(setOf(netease.cacheIdentity()), selection.ids)
        selection.update(listOf(qq))
        assertTrue(selection.ids.isEmpty())
    }

    @Test fun bulkRemovalPersistsIndexOnceAndPreservesMembershipAcrossRestart() {
        var saved = "{}"
        var writes = 0
        val index = MusicCacheIndex({ saved }, { saved = it; writes++ })
        index.replacePrimary("QQ:account", listOf("QQ|0"), ordered = true)
        repeat(128) { index.importIdentity("key-$it", "QQ|$it", it + 1L) }
        writes = 0
        repeat(128) { index.forget("key-$it", persist = false) }
        assertEquals(0, writes)
        index.flush()
        assertEquals(1, writes)
        index.flush()
        assertEquals(1, writes)
        val restarted = MusicCacheIndex({ saved }, { saved = it })
        assertTrue(restarted.isPrimary("QQ|0"))
        repeat(128) { assertEquals(0L, restarted.resource("key-$it")!!.entered) }
    }

    @Test fun menusFitPhoneAndTabletSafeAreasAtAllEdges() {
        for (safe in listOf(Rect(12f, 40f, 363f, 560f), Rect(36f, 48f, 1220f, 720f))) {
            val size = IntSize(320, 500)
            for (origin in listOf(safe.topLeft, safe.bottomRight, safe.center)) {
                val offset = settingsMenuOffset(origin, safe, size)
                assertTrue(offset.x >= safe.left && offset.x + size.width <= safe.right)
                assertTrue(offset.y >= safe.top && offset.y + size.height <= safe.bottom)
            }
        }
    }

    @Test fun menuUsesMeasuredHeightAndFlipsAboveBottomControls() {
        val safe = Rect(12f, 36f, 388f, 600f)
        assertEquals(Offset(32f, 150f), settingsMenuOffset(Offset(200f, 550f), safe, IntSize(336, 400)))
        assertEquals(Offset(32f, 36f), settingsMenuOffset(Offset(200f, 550f), safe, IntSize(336, 564)))
    }

    @Test fun fiftyGigabytesParticipatesInBothQuotasWithoutAddingThem() {
        assertEquals(50L * 1024 * 1024 * 1024, CacheCapacity.GB50.bytes)
        assertEquals(CacheCapacity.GB50, CacheCapacity.fromLegacy(50))
        assertEquals(CacheCapacity.GB50.bytes, CacheLimits(CacheCapacity.GB50, CacheCapacity.GB20).maximum)
        assertEquals(CacheCapacity.GB50.bytes, CacheLimits(CacheCapacity.GB20, CacheCapacity.GB50).maximum)
    }
}
