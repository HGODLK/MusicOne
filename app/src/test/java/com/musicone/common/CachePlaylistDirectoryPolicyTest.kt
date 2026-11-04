package com.musicone

import org.junit.Assert.*
import org.junit.Test
import com.musicone.source.sdk.*

class CachePlaylistDirectoryPolicyTest {
    private fun playlist(id: String, source: MusicSource = MusicSource.KUGOU, title: String = "歌单") =
        MusicPlaylist(id, source, title, "", "", 0, 0, 0, "", emptyList())

    @Test fun missingPackageRetainsSnapshotsAndOptionalPolicyFiltersCurrentDirectory() {
        val recommended = playlist("old-recommendation")
        val library = playlist("account-list")
        val policy = object : SourceCollectionCachePolicy {
            override fun includesInCacheLibrary(collection: SourceCollection) = collection.stableId == "account-list"
        }
        assertTrue(isCacheLibraryPlaylist(recommended, null))
        assertFalse(isCacheLibraryPlaylist(recommended, policy))
        assertTrue(isCacheLibraryPlaylist(library, policy))
        assertFalse(isCacheLibraryPlaylist(playlist("local-cached-qq", MusicSource.QQ), null))
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

    @Test fun providerRefreshPurgesWarmRecommendationStateEvenWhenTheAccountHasNoPlaylists() {
        val recommended = playlist("kugou-123", title = "首页推荐")
        val own = playlist("kugou-cloudlist:456").copy(isOwned = true)
        val local = playlist("library-one", MusicSource.LOCAL)
        assertEquals(listOf(local, own), replaceCacheProviderPlaylists(listOf(recommended, local), MusicSource.KUGOU, listOf(own)))
        assertEquals(listOf(local), replaceCacheProviderPlaylists(listOf(recommended, own, local), MusicSource.KUGOU, emptyList()))
        assertEquals(listOf(recommended, own), canonicalCachePlaylists(listOf(recommended, own, playlist("cache-track:456"))))
    }


    private val supplementalId = "org.example.supplemental"
    private fun collection(count: Int = 1) = SourceRemoteCollection(TrackIdentity(supplementalId, "42", "folder"), "收藏夹", count, SourceArtwork("folder-cover"))
    private fun content(id: String, cover: String = "", valid: Boolean = true): SourceContent {
        val identity = TrackIdentity(supplementalId, "42", id)
        return SourceContent(identity, "歌曲", artwork = SourceArtwork(cover), access = TrackAccessInfo(playable = valid),
            playableTrack = SourceTrackDetails(SourceTrack(identity, "歌曲"), "track-$id", access = TrackAccessInfo(playable = valid)))
    }
    @Test fun supplementalCollectionsKeepIncompletePaginationVisibleToTheLoader() {
        val folder = collection()
        val item = content("child", "")
        val partial = sourceCacheCollectionSnapshot(folder, SourcePage(listOf(item), "next"))
        assertEquals(supplementalId, partial.source.id)
        assertEquals(folder.identity.remoteId, partial.remoteKey)
        assertTrue(partial.count > partial.tracks.size)
        val complete = sourceCacheCollectionSnapshot(folder, SourcePage(listOf(item), null))
        assertEquals(1, complete.count)
        assertEquals("child", complete.tracks.single().remoteKey)
        assertEquals("folder-cover", complete.artworkUrl)
    }
    @Test fun emptySupplementalCollectionDoesNotInventASong() {
        assertEquals(0, sourceCacheCollectionSnapshot(collection(0), SourcePage(emptyList(), null)).count)
    }
    @Test fun collectionCoverFollowsLatestValidContentInsteadOfItsOldCover() {
        val items = listOf(content("deleted", "deleted-cover", false), content("latest", "latest-cover"), content("older", "older-cover"))
        assertEquals("latest-cover", sourceCacheCollectionSnapshot(collection(3), SourcePage(items, null)).artworkUrl)
    }
}
