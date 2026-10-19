package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class CachePlaylistDirectoryPolicyTest {
    private fun playlist(id: String, source: MusicSource = MusicSource.KUGOU, title: String = "歌单") =
        MusicPlaylist(id, source, title, "", "", 0, 0, 0, "", emptyList())

    @Test fun rejectsKugouRecommendationsButRetainsCreatedAndCollectedCloudLists() {
        assertFalse(isCacheLibraryPlaylist(playlist("kugou-123")))
        assertFalse(isCacheLibraryPlaylist(playlist("kugou-special:456")))
        assertTrue(isCacheLibraryPlaylist(playlist("kugou-cloudlist:123").copy(isOwned = true)))
        assertTrue(isCacheLibraryPlaylist(playlist("kugou-cloudlist:456").copy(isOwned = false)))
        assertFalse(isCacheLibraryPlaylist(playlist("local-cached-qq", MusicSource.QQ)))
    }

    @Test fun playlistSearchTrimsWhitespaceAndIgnoresCaseWithoutChangingOrder() {
        val first = playlist("one", title = "My Mix")
        val second = playlist("two", title = "夜晚收藏夹")
        val third = playlist("three", title = "Mix 2")
        val all = listOf(first, second, third)
        assertEquals(listOf(first, third), all.matchingCachePlaylistQuery(" mix "))
        assertEquals(listOf(second), all.matchingCachePlaylistQuery("收藏夹"))
        assertSame(all, all.matchingCachePlaylistQuery(" "))
    }

    @Test fun biliFoldersRemainBiliAndKeepIncompletePaginationVisibleToTheLoader() {
        val folder = BiliFavFolder(123, "音乐收藏夹", 1, "https://example.com/cover")
        val video = BiliVideoItem(1, "BV1abcdefghj", 456, "歌曲", "", "UP", 120, false)
        val partial = biliCachePlaylist(folder, BiliFolderSnapshot(listOf(video), 1, true))
        assertEquals(MusicSource.BILIBILI, partial.source)
        assertEquals("bili-folder-123", partial.id)
        assertTrue(partial.count > partial.tracks.size)
        val complete = biliCachePlaylist(folder, BiliFolderSnapshot(listOf(video), 1, false))
        assertEquals(1, complete.count)
        assertEquals(456L, complete.tracks.single().bilibiliCid)
        assertEquals("https://example.com/cover", complete.artworkUrl)
    }

    @Test fun emptyBiliFolderDoesNotInventASong() {
        val folder = BiliFavFolder(1, "空收藏夹", 0)
        assertEquals(0, biliCachePlaylist(folder, null).count)
        assertEquals(0, biliCachePlaylist(folder, BiliFolderSnapshot(emptyList(), 1, false)).count)
    }

    @Test fun providerRefreshPurgesWarmRecommendationStateEvenWhenTheAccountHasNoPlaylists() {
        val recommended = playlist("kugou-123", title = "首页推荐")
        val own = playlist("kugou-cloudlist:456").copy(isOwned = true)
        val local = playlist("library-one", MusicSource.LOCAL)
        assertEquals(listOf(local, own), replaceCacheProviderPlaylists(listOf(recommended, local), MusicSource.KUGOU, listOf(own)))
        assertEquals(listOf(local), replaceCacheProviderPlaylists(listOf(recommended, own, local), MusicSource.KUGOU, emptyList()))
        assertEquals(listOf(own), canonicalCachePlaylists(listOf(recommended, own, playlist("cache-track:456"))))
    }

    @Test fun biliCoverFollowsLatestValidVideoInsteadOfTheFoldersOldCover() {
        val old = BiliFavFolder(1, "音乐", 3, "old-fixed-cover")
        val deleted = BiliVideoItem(1, "BV-deleted", 1, "已失效视频", "deleted-cover", "", 0, true)
        val latest = deleted.copy(aid = 2, bvid = "BV-latest", cover = "latest-cover", isInvalid = false)
        val older = latest.copy(aid = 3, bvid = "BV-older", cover = "older-cover")
        val snapshot = BiliFolderSnapshot(listOf(deleted, latest, older), 1, false)
        assertEquals("latest-cover", latestBiliFolderCover(snapshot.videos))
        assertEquals("latest-cover", biliCachePlaylist(old, snapshot).artworkUrl)
    }
}
