package com.musicone

import com.musicone.source.sdk.SourceArtwork
import com.musicone.source.sdk.SourceRemoteCollection
import com.musicone.source.sdk.TrackIdentity
import org.junit.Assert.*
import org.junit.Test

class CacheDirectoryMergeTest {
    private fun playlist(id: String, source: MusicSource = MusicSource.QQ) =
        MusicPlaylist(id, source, id, "", "", 1, 0, 0, "", emptyList())

    @Test fun largeDirectoryScansOldItemsOnceWithoutTruncatingOrReordering() {
        val values = (0 until 10_000).map { playlist(it.toString()) }
        var reads = 0
        val previous = object : AbstractList<MusicPlaylist>() {
            override val size get() = values.size
            override fun get(index: Int): MusicPlaylist { reads++; return values[index] }
        }
        val fresh = values.reversed()
        val merged = mergeCacheDirectoryTracks(fresh, previous)
        assertEquals(fresh, merged)
        assertEquals(10_000, merged.size)
        assertTrue("旧目录应只建立一次索引，实际读取 $reads 次", reads <= values.size)
    }

    @Test fun mergesOnlySameSourceIdentityAndRetainsFreshMetadata() {
        val qq = playlist("same").copy(tracks = listOf(MusicTrack("qq-song", MusicSource.QQ, "歌", "", "", 1, 0, 0, "", "")))
        val local = playlist("same", MusicSource.LOCAL)
        val fresh = qq.copy(title = "新标题", tracks = emptyList(), artworkUrl = "new-cover")
        val merged = mergeCacheDirectoryTracks(listOf(fresh, local), listOf(qq))
        assertEquals(qq.tracks, merged[0].tracks)
        assertEquals("新标题", merged[0].title)
        assertEquals("new-cover", merged[0].artworkUrl)
        assertTrue(merged[1].tracks.isEmpty())
        val old = qq.copy(artworkUrl = "old-cover")
        assertEquals("old-cover", mergeCacheDirectoryTracks(listOf(fresh.copy(artworkUrl = "")), listOf(old)).single().artworkUrl)
    }

    @Test fun metadataOnlyFolderKeepsCountIdentityAndArtworkUntilOpened() {
        val folder = SourceRemoteCollection(TrackIdentity("org.example.supplemental", "account", "folder"),
            "收藏夹", 10_000, SourceArtwork("latest-cover"))
        val result = sourceCacheCollectionDirectorySnapshot(folder)
        assertEquals(10_000, result.count)
        assertTrue(result.tracks.isEmpty())
        assertEquals("folder", result.remoteKey)
        assertEquals("latest-cover", result.artworkUrl)
        assertEquals(1, sourceCacheCollectionDirectorySnapshot(folder.copy(count = null)).count)
        assertEquals(0, sourceCacheCollectionDirectorySnapshot(folder.copy(count = 0)).count)
    }
}
