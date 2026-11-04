package com.musicone.source.kugou

import com.musicone.source.sdk.*
import org.junit.Assert.*
import org.junit.Test

class KugouCacheCollectionPolicyTest {
    @Test fun onlyAccountCloudCollectionsEnterCacheDirectory() {
        val catalog = KugouSourceCatalog { error("目录筛选不应访问账号或网络") }
        fun collection(id: String, owned: Boolean = false) = SourceCollection(
            TrackIdentity(KUGOU_SOURCE_ID, null, id), id, "歌单", owned = owned)
        assertFalse(catalog.includesInCacheLibrary(collection("kugou-123")))
        assertFalse(catalog.includesInCacheLibrary(collection("kugou-special:456")))
        assertTrue(catalog.includesInCacheLibrary(collection("kugou-cloudlist:123", true)))
        assertTrue(catalog.includesInCacheLibrary(collection("kugou-cloudlist:456", false)))
        assertFalse(catalog.includesInCacheLibrary(collection("kugou-cloudlist:123").copy(
            identity = TrackIdentity("org.example.other", null, "123"))))
    }

    @Test fun everyLibrarySectionRejectsRecommendationsButKeepsSavedPersonalCollections() {
        val own = MusicPlaylist("kugou-cloudlist:100", title = "我的歌单", isOwned = true)
        val saved = MusicPlaylist("kugou-cloudlist:200", title = "收藏的歌单", isOwned = false)
        val recommendation = MusicPlaylist("kugou-300", title = "首页推荐")
        val library = KugouLibrary(own, listOf(recommendation, own, own), listOf(recommendation, saved))
        assertEquals(listOf(own), library.cacheCollections(SourceLibrarySection.FAVORITE_TRACKS))
        assertEquals(listOf(own), library.cacheCollections(SourceLibrarySection.CREATED_COLLECTIONS))
        assertEquals(listOf(saved), library.cacheCollections(SourceLibrarySection.SAVED_COLLECTIONS))
        assertTrue(KugouLibrary(recommendation, emptyList(), emptyList())
            .cacheCollections(SourceLibrarySection.FAVORITE_TRACKS).isEmpty())
    }
}
