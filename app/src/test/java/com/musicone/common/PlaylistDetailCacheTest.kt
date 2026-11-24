package com.musicone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaylistDetailCacheTest {
    private fun track(index: Int) = MusicTrack("qq-$index", MusicSource.QQ, "歌曲$index", "歌手", "",
        180_000, 0, 0, "", "")
    private fun playlist(ids: IntRange, count: Int = ids.count()) =
        MusicPlaylist("qq-owned", MusicSource.QQ, "我的歌单", "", "", count, 0, 0, "", ids.map(::track), isOwned = true)

    @Test fun 第一页与缓存开头一致且总数相同才沿用缓存() {
        val cached = playlist(0 until 250)
        assertTrue(playlistCacheStillFresh(cached, playlist(0 until 100, count = 250)))
        // 新加了一首：开头不同、总数也不同。
        assertFalse(playlistCacheStillFresh(cached, playlist(-1 until 99, count = 251)))
        // 删了靠后的一首：开头相同但总数变了。
        assertFalse(playlistCacheStillFresh(cached, playlist(0 until 100, count = 249)))
        assertFalse(playlistCacheStillFresh(cached, playlist(IntRange.EMPTY, count = 250)))
    }

    @Test fun 后台核对期间继续显示缓存且完整后一次性替换() = runBlocking {
        val shell = playlist(IntRange.EMPTY, count = 250)
        val loader = PlaylistPagingController { _, cursor ->
            val offset = cursor?.offset ?: 0
            val end = (offset + 100).coerceAtMost(251)
            // 远端比缓存多一首，排在最前。
            PlaylistTrackPage(shell.copy(count = 251, tracks = (offset - 1 until end - 1).map(::track)),
                nextPlaylistTrackCursor(offset, end - offset, 251))
        }
        val entry = loader.entry(shell)
        entry.cached = playlist(0 until 250)
        loader.first(shell)
        assertEquals(250, entry.displayed?.tracks?.size)
        loader.more(shell)
        assertEquals("qq-0", entry.displayed?.tracks?.first()?.id)
        loader.complete(shell)
        assertEquals(251, entry.displayed?.tracks?.size)
        assertEquals("qq--1", entry.displayed?.tracks?.first()?.id)
    }

    @Test fun 缓存有效时沿用缓存歌曲并结束分页() = runBlocking {
        val shell = playlist(IntRange.EMPTY, count = 250)
        val loader = PlaylistPagingController { _, _ ->
            PlaylistTrackPage(shell.copy(title = "改过名", tracks = (0 until 100).map(::track)),
                nextPlaylistTrackCursor(0, 100, 250))
        }
        val entry = loader.entry(shell)
        entry.cached = playlist(0 until 250)
        loader.first(shell)
        assertTrue(playlistCacheStillFresh(entry.cached!!, entry.page!!.playlist))
        entry.adoptCached()
        assertNull(entry.page?.next)
        assertEquals(250, entry.displayed?.tracks?.size)
        assertEquals("改过名", entry.displayed?.title)
    }

    @Test fun 移除歌曲同时作用于缓存且内容不变不重写() {
        val shell = playlist(IntRange.EMPTY, count = 3)
        val entry = PlaylistPagingEntry(shell)
        entry.cached = playlist(0 until 3)
        entry.remove("qq-1")
        assertEquals(listOf("qq-0", "qq-2"), entry.displayed?.tracks?.map { it.id })
        assertEquals(2, entry.displayed?.count)
        assertFalse(playlistCacheNeedsSave(playlist(0 until 3), playlist(0 until 3)))
        assertTrue(playlistCacheNeedsSave(playlist(0 until 3), playlist(0 until 4)))
        assertTrue(playlistCacheNeedsSave(null, playlist(0 until 3)))
        assertFalse(playlistCacheNeedsSave(null, shell))
        assertNotNull(playlist(0 until 3).completeTracksOrNull())
        assertNull(playlist(0 until 3, count = 10).completeTracksOrNull())
    }
}
