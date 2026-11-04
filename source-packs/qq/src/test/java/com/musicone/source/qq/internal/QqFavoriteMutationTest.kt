package com.musicone.source.qq.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QqFavoriteMutationTest {
    @Test
    fun favoriteMutationKeepsBatchedQqSongIdsTypesAndTargetState() {
        val mutation = qqFavoriteMutation(listOf(
            qqTrack("qq-first", catalogId = "12345", providerType = 7),
            qqTrack("qq-second", catalogId = "67890", providerType = 9),
        ), true)

        assertEquals(2, mutation.songs.size)
        assertEquals(12345L, mutation.songs[0].songId)
        assertEquals(7, mutation.songs[0].songType)
        assertEquals(67890L, mutation.songs[1].songId)
        assertEquals(9, mutation.songs[1].songType)
        assertEquals("normal", QQ_FAVORITE_WRITE_SOURCE)
        assertTrue(mutation.liked)
        assertTrue(qqFavoriteMutationSucceeded(0, 0))
        assertFalse(qqFavoriteMutationSucceeded(0, 1))
        assertFalse(qqFavoriteMutationSucceeded(null, 0))
    }

    private fun qqTrack(
        id: String,
        catalogId: String = "1",
        providerType: Int = 0,
        previewUrl: String = "",
    ) = MusicTrack(
        id = id,
        source = MusicSource.QQ,
        title = "测试歌曲",
        artists = "测试歌手",
        album = "测试专辑",
        durationMs = 180_000L,
        artworkStart = 0L,
        artworkEnd = 0L,
        artworkMark = "测",
        previewUrl = previewUrl,
        catalogId = catalogId,
        providerType = providerType,
    )
}
