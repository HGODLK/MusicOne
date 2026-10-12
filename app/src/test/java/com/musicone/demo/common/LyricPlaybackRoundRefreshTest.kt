package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class LyricPlaybackRoundRefreshTest {
    private val presentation = LyricPresentation("a", MusicSource.QQ, 180_000L, emptyList())

    @Test fun unloadedSongResumeKeepsItsSlotAndPositionWithAFreshSeekOwner() {
        val old = LyricWindow(presentation, 2f)
        old.playback.update(PlaybackProgressSnapshot("a", 45_000L, 3L))
        val stale = PlayerSeekPreview().requestProgressLyricSeek("a", 45_000L, .5f, generation = 3L)
        val next = requireNotNull(old.refreshPlaybackRound(presentation, PlaybackProgressSnapshot("a", 45_000L, 4L)))
        assertNotSame(old, next)
        assertEquals(2f, next.slot, 0f)
        assertEquals(45_000L, next.playback.positionMs)
        assertEquals(4L, next.playback.generation)
        assertNull(next.playback.progressSeek(stale))
    }

    @Test fun differentTrackOrPlaybackOwnerStillUsesTheCrossTrackPath() {
        val old = LyricWindow(presentation)
        assertNull(old.refreshPlaybackRound(presentation.copy(id = "b"), PlaybackProgressSnapshot("b", 0L, 4L)))
        assertNull(old.refreshPlaybackRound(presentation, PlaybackProgressSnapshot("b", 0L, 4L)))
        assertNull(old.refreshPlaybackRound(presentation.copy(source = MusicSource.KUGOU), PlaybackProgressSnapshot("a", 0L, 4L)))
    }
}
