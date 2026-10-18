package com.musicone.demo

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class SystemMediaMetadataTest {
    private fun track(id: String = "current", durationMs: Long = 240_000L) = MusicTrack(
        id, MusicSource.QQ, "补齐歌名", "补齐歌手", "补齐专辑", durationMs,
        0, 0, "", "",
    )

    @Test fun expectedFullSongDurationDoesNotBecomeTheTrialSourceDuration() {
        val unknown = systemMediaMetadata(track())
        val trial = systemMediaMetadata(track(), durationMs = 30_000L)
        assertNull(unknown.durationMs)
        assertEquals(30_000L, trial.durationMs)
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, trial.mediaType)
        assertEquals(true, trial.isPlayable)
        assertEquals(false, trial.isBrowsable)
    }

    @Test fun enrichmentOnlyReplacesMetadataAndPreservesTheCurrentItemIdentity() {
        val requestMetadata = MediaItem.RequestMetadata.Builder().setSearchQuery("原请求").build()
        val item = MediaItem.Builder().setMediaId("current")
            .setRequestMetadata(requestMetadata).build()
        val player = RecordingPlayer(item, 30_000L)
        assertTrue(refreshSystemMediaMetadata(player.value, track()))
        assertEquals(listOf("replaceMediaItem"), player.mutations)
        val replacement = requireNotNull(player.replacement)
        assertEquals("current", replacement.mediaId)
        assertEquals("原请求", replacement.requestMetadata.searchQuery)
        assertEquals("补齐歌名", replacement.mediaMetadata.title)
        assertEquals("补齐歌手", replacement.mediaMetadata.artist)
        assertEquals(30_000L, replacement.mediaMetadata.durationMs)
    }

    @Test fun anOldTracksEnrichmentCannotReplaceTheNewSong() {
        val player = RecordingPlayer(MediaItem.Builder().setMediaId("new").build())
        assertFalse(refreshSystemMediaMetadata(player.value, track("old")))
        assertTrue(player.mutations.isEmpty())
        assertNull(player.replacement)
    }

    @Test fun missingNewArtworkPreservesTheSameSongsEmbeddedCover() {
        val cover = byteArrayOf(1, 2, 3)
        val previous = MediaMetadata.Builder().setArtworkData(cover, null).build()
        val metadata = systemMediaMetadata(track(), previous)
        assertArrayEquals(cover, metadata.artworkData)
    }

    @Test fun noCurrentItemOrReadOnlyPlayerCannotBeReplaced() {
        val empty = RecordingPlayer(null)
        val readOnly = RecordingPlayer(MediaItem.Builder().setMediaId("current").build(), editable = false)
        assertFalse(refreshSystemMediaMetadata(empty.value, track()))
        assertFalse(refreshSystemMediaMetadata(readOnly.value, track()))
        assertTrue(empty.mutations.isEmpty())
        assertTrue(readOnly.mutations.isEmpty())
    }

    private class RecordingPlayer(
        item: MediaItem?,
        durationMs: Long = C.TIME_UNSET,
        editable: Boolean = true,
    ) {
        val mutations = mutableListOf<String>()
        var replacement: MediaItem? = null
        val value = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getCurrentMediaItem" -> item
                "getCurrentMediaItemIndex" -> 0
                "getDuration" -> durationMs
                "isCommandAvailable" -> editable
                "replaceMediaItem" -> {
                    mutations += method.name
                    replacement = args!![1] as MediaItem
                    null
                }
                else -> error("后补信息不应调用 ${method.name}")
            }
        } as Player
    }
}
