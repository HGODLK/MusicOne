package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicSearchResultTest {
    @Test
    fun zeroQqMidIsRejectedBeforeItCanBecomeADuplicateLazyGridKey() {
        assertEquals(false, isValidQqTrackMid("0"))
        assertEquals(false, isValidQqTrackMid(""))
        assertEquals(true, isValidQqTrackMid("002WBZaC2wsLqC"))
    }

    @Test
    fun duplicateProviderKeysAreRemovedBeforeComposeRendersThem() {
        val results = uniqueSearchResults(
            listOf(track("qq-0", "旧曲一"), track("qq-0", "旧曲二"), track("qq-valid", "有效歌曲")),
        )

        assertEquals(listOf("qq-0", "qq-valid"), results.map(MusicTrack::id))
        assertEquals("旧曲一", results.first().title)
    }

    private fun track(id: String, title: String) = MusicTrack(
        id = id,
        source = MusicSource.QQ,
        title = title,
        artists = "歌手",
        album = "专辑",
        durationMs = 180_000L,
        artworkStart = 0L,
        artworkEnd = 0L,
        artworkMark = title.take(1),
        previewUrl = "",
    )
}
