package com.musicone

import org.junit.Assert.*
import org.junit.Test

class LyricPlaybackRoundRefreshTest {
    private val presentation = LyricPresentation("a", MusicSource.QQ, 180_000L, emptyList())

    @Test fun sameSongNewRoundRebindsTheShownWindowInPlace() {
        val old = LyricWindow(presentation, 2f)
        old.ready.complete(Unit)
        old.playback.update(PlaybackProgressSnapshot("a", 45_000L, 3L))
        val stale = PlayerSeekPreview().requestProgressLyricSeek("a", 45_000L, .5f, generation = 3L)
        assertTrue(old.refreshPlaybackRound(presentation, PlaybackProgressSnapshot("a", 46_000L, 4L)))
        // 原窗口保持已就绪，不会退回到等待准备的不可见状态。
        assertTrue(old.ready.isCompleted)
        assertEquals(2f, old.slot, 0f)
        assertEquals(46_000L, old.playback.positionMs)
        assertEquals(4L, old.playback.generation)
        assertNull(old.playback.progressSeek(stale))
        old.playback.update(PlaybackProgressSnapshot("a", 47_000L, 4L))
        assertEquals(47_000L, old.playback.positionMs)
    }

    @Test fun differentTrackOrPlaybackOwnerStillUsesTheCrossTrackPath() {
        val old = LyricWindow(presentation)
        old.playback.update(PlaybackProgressSnapshot("a", 1_000L, 3L))
        assertFalse(old.refreshPlaybackRound(presentation.copy(id = "b"), PlaybackProgressSnapshot("b", 0L, 4L)))
        assertFalse(old.refreshPlaybackRound(presentation, PlaybackProgressSnapshot("b", 0L, 4L)))
        assertFalse(old.refreshPlaybackRound(presentation.copy(source = MusicSource.KUGOU), PlaybackProgressSnapshot("a", 0L, 4L)))
        assertEquals(3L, old.playback.generation)
    }
}
