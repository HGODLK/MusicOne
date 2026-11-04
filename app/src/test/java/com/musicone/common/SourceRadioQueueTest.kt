package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRadioQueueTest {
    @Test
    fun radioKeepsExactlyFiveTracksAhead() {
        val window = SourceRadioQueueWindow()
        val initial = window.start((1..20).map(::track))

        assertEquals((1..6).map { "qq-$it" }, initial.map(MusicTrack::id))
        assertEquals(0, window.requestSize(initial, "qq-1", advanceAfterLoad = false))
        assertEquals(1, window.requestSize(initial, "qq-2", advanceAfterLoad = false))
        assertEquals(6, window.requestSize(listOf(track(1)), "qq-1", advanceAfterLoad = true))
    }

    @Test
    fun appendedRadioTracksKeepOrderAndRemoveDuplicates() {
        val queue = listOf(track(1), track(2))
        val result = appendSourceRadioTracks(queue, listOf(track(2), track(3), track(3), track(4)))

        assertEquals(listOf("qq-1", "qq-2", "qq-3", "qq-4"), result.map(MusicTrack::id))
        assertEquals("qq-2", nextSourceRadioTrack(result, "qq-1")?.id)
        assertEquals("qq-1", previousSourceRadioTrack(result, "qq-2")?.id)
        assertEquals(null, nextSourceRadioTrack(result, "qq-4"))
        assertEquals(null, nextSourceRadioTrack(result, "qq-missing"))
        assertEquals(null, previousSourceRadioTrack(result, "qq-1"))
    }

    @Test
    fun radioWindowGrowsHistoryGraduallyAndCapsItAtThirty() {
        val window = SourceRadioQueueWindow()
        val initial = window.start((1..6).map(::track))
        val centered = window.append(initial, (7..41).map(::track), "qq-36")

        assertEquals(36, centered.size)
        assertEquals("qq-6", centered.first().id)
        assertEquals("qq-36", centered[30].id)
        assertEquals("qq-41", centered.last().id)
        assertTrue((1..41).all { "qq-$it" in window.seenIds() })
    }

    private fun track(index: Int) = MusicTrack(
        id = "qq-$index",
        source = MusicSource.QQ,
        title = "歌曲 $index",
        artists = "歌手",
        album = "专辑",
        durationMs = 180_000L,
        artworkStart = 0L,
        artworkEnd = 0L,
        artworkMark = index.toString(),
        previewUrl = "",
    )
}
