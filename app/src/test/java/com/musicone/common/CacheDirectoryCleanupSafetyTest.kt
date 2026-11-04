package com.musicone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CacheDirectoryCleanupSafetyTest {
    @Test fun unavailableDirectoryNeverDispatchesDeletionAfterAnotherSourceSucceeded() = runBlocking {
        val available = MusicSource.fromId("org.example.cache-main")
        val missing = MusicSource.fromId("org.example.cache-supplemental")
        val track = MusicTrack("same-remote", available, "缓存歌曲", "歌手", "", 1000, 0, 0, "", "")
        val playlist = MusicPlaylist("remote-collection", available, "歌单", "", "", 1, 0, 0, "", listOf(track))
        var deletions = 0
        val blocked = CachePlaylistCleanupPlan(listOf(playlist), setOf(missing))
        try {
            clearOutsideConfirmedCachePlaylists(blocked) { deletions++ }
            fail("部分来源已成功不能将未知来源当空目录执行全局删除")
        } catch (expected: CacheDirectoryUnconfirmedException) {
            assertTrue(expected.message.orEmpty().contains("已保留缓存"))
        }
        assertEquals(0, deletions)
        clearOutsideConfirmedCachePlaylists(CachePlaylistCleanupPlan(listOf(playlist))) { protected ->
            deletions++
            assertEquals(setOf(track.cacheIdentity()), protected)
        }
        assertEquals(1, deletions)
    }
}
