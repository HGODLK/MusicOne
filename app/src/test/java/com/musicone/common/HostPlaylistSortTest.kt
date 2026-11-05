package com.musicone

import com.musicone.source.sdk.SourceCollectionSortOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HostPlaylistSortTest {
    private fun playlist(id: String, data: Map<String, String> = emptyMap(), source: MusicSource = MusicSource.QQ) =
        MusicPlaylist(id, source, "歌单", "", "", 0, 0, 0, "歌", emptyList(), providerData = data)

    private val today = playlist("qq-daily-2026-11-04", DAILY_PLAYLIST_PROVIDER_DATA)

    @Test fun 每日推荐第二天沿用同一个排序键() {
        assertEquals(hostPlaylistSortKey(today, "1", emptyList()),
            hostPlaylistSortKey(playlist("qq-daily-2026-11-05", DAILY_PLAYLIST_PROVIDER_DATA), "1", emptyList()))
    }

    @Test fun 每日推荐沿用旧版键格式() {
        assertEquals("${MusicSource.QQ.id}:1", hostPlaylistSortKey(today, "1", emptyList()))
    }

    @Test fun 不同账号分开保存() {
        assertNotEquals(hostPlaylistSortKey(today, "1", emptyList()), hostPlaylistSortKey(today, "2", emptyList()))
        val normal = playlist("netease-1", source = MusicSource.NETEASE)
        assertNotEquals(hostPlaylistSortKey(normal, "1", emptyList()), hostPlaylistSortKey(normal, "2", emptyList()))
    }

    @Test fun 音源提供完整排序时不接管() {
        assertNull(hostPlaylistSortKey(today, "1", HOST_PLAYLIST_SORT_OPTIONS))
    }

    @Test fun 音源只提供部分排序时由宿主接管() {
        assertNotNull(hostPlaylistSortKey(today, "1", listOf(SourceCollectionSortOption("NAME", "歌曲名称"))))
    }

    @Test fun 不同歌单分开保存() {
        assertNotEquals(hostPlaylistSortKey(playlist("netease-1"), "1", emptyList()),
            hostPlaylistSortKey(playlist("netease-2"), "1", emptyList()))
    }

    @Test fun 账号和歌单边界不会混淆() {
        assertNotEquals(hostPlaylistSortKey(playlist("2:3"), "1", emptyList()),
            hostPlaylistSortKey(playlist("3"), "1:2", emptyList()))
    }

    @Test fun 临时歌单也能保存() {
        assertNotNull(hostPlaylistSortKey(playlist("qq-recent:songs", mapOf("templateEphemeral" to "true")), "1", emptyList()))
    }

    @Test fun 本地歌单不随账号变化() {
        val local = playlist("library-1", source = MusicSource.LOCAL)
        assertEquals(hostPlaylistSortKey(local, null, emptyList()), hostPlaylistSortKey(local, "1", emptyList()))
    }

    @Test fun 排序选项与按钮循环一致() {
        assertEquals(PlaylistSort.entries.map { it.name }, HOST_PLAYLIST_SORT_OPTIONS.map { it.id })
    }
}
