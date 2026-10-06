package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QqPlaybackChangesTest {
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

    @Test
    fun qqSnapshotFiltersOtherSourcesAndDropsTemporaryPlaybackData() {
        val current = qqTrack("qq-current", previewUrl = "https://expired.example/song.mp3")
            .copy(lyrics = listOf(TimedLyric(1_000L, "歌词")))
        val other = current.copy(id = "netease-other", source = MusicSource.NETEASE)
        val snapshot = MusicOneUiState(
            currentTrack = current,
            queue = listOf(other, current),
            shuffle = true,
            repeatMode = RepeatMode.ONE,
            qqRadioActive = true,
        ).toQqPlaybackSnapshot(32_000L)

        requireNotNull(snapshot)
        assertEquals(listOf("qq-current"), snapshot.queue.map(MusicTrack::id))
        assertEquals("", snapshot.queue.single().previewUrl)
        assertTrue(snapshot.queue.single().lyrics.isEmpty())
        assertEquals(32_000L, snapshot.positionMs)
        assertTrue(snapshot.shuffle)
        assertEquals(RepeatMode.ONE, snapshot.repeatMode)
        assertTrue(snapshot.qqRadioActive)
    }

    @Test
    fun restoredQqSnapshotIsPausedAndKeepsQueueOrder() {
        val first = qqTrack("qq-first")
        val second = qqTrack("qq-second")
        val snapshot = QqPlaybackSnapshot(
            queue = listOf(first, second),
            currentTrackId = second.id,
            positionMs = 9_000L,
            shuffle = true,
            repeatMode = RepeatMode.OFF,
            qqRadioActive = true,
        )
        val restored = MusicOneUiState(isPlaying = true, playerExpanded = true)
            .withQqPlaybackSnapshot(snapshot)

        assertEquals(listOf(first, second), restored.queue)
        assertEquals(second, restored.currentTrack)
        assertFalse(restored.isPlaying)
        assertFalse(restored.playerExpanded)
        assertTrue(restored.shuffle)
        assertEquals(RepeatMode.OFF, restored.repeatMode)
        assertTrue(restored.qqRadioActive)
    }

    @Test
    fun missingQqSnapshotProducesEmptyPlayerState() {
        val restored = MusicOneUiState(
            currentTrack = qqTrack("qq-old"),
            queue = listOf(qqTrack("qq-old")),
            isPlaying = true,
        ).withQqPlaybackSnapshot(null)

        assertNull(restored.currentTrack)
        assertTrue(restored.queue.isEmpty())
        assertFalse(restored.isPlaying)
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
