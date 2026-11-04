package com.musicone

import com.musicone.source.sdk.SourceCollectionSortOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailyPlaylistSortTest {
    private fun playlist(id: String, data: Map<String, String>) =
        MusicPlaylist(id, MusicSource.QQ, "每日推荐", "", "", 0, 0, 0, "日", emptyList(), providerData = data)

    private val today = playlist("qq-daily-2026-11-04", DAILY_PLAYLIST_PROVIDER_DATA)

    @Test fun 第二天沿用同一个排序键() {
        assertEquals(dailyPlaylistSortKey(today, "1", emptyList()),
            dailyPlaylistSortKey(playlist("qq-daily-2026-11-05", DAILY_PLAYLIST_PROVIDER_DATA), "1", emptyList()))
    }

    @Test fun 不同账号分开保存() {
        assertNotEquals(dailyPlaylistSortKey(today, "1", emptyList()), dailyPlaylistSortKey(today, "2", emptyList()))
    }

    @Test fun 音源提供排序时不接管() {
        assertNull(dailyPlaylistSortKey(today, "1", listOf(SourceCollectionSortOption("NAME", "歌曲名称"))))
    }

    @Test fun 其他临时歌单不接管() {
        assertNull(dailyPlaylistSortKey(playlist("qq-recent:songs", mapOf("templateEphemeral" to "true")), "1", emptyList()))
    }

    @Test fun 排序选项与按钮循环一致() {
        assertEquals(PlaylistSort.entries.map { it.name }, DAILY_PLAYLIST_SORT_OPTIONS.map { it.id })
    }
}
