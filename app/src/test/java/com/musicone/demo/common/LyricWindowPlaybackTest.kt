package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class LyricWindowPlaybackTest {
    @Test fun rapidReturnAfterTheImmediateFirstClickPreparesTheRestartAtZero() {
        val firstClick = LyricWindowPlayback("b")
        firstClick.update(PlaybackProgressSnapshot("b", 300L))
        assertFalse(firstClick.canReturnTo("b", restarting = true))
        val returningPreview = LyricWindowPlayback("b")
        returningPreview.update(PlaybackProgressSnapshot("b", 300L), browsing = true)
        assertEquals(0L, returningPreview.positionMs)
        assertTrue(returningPreview.canReturnTo("b", restarting = true))
        returningPreview.update(PlaybackProgressSnapshot("b", 0L))
        assertEquals(0L, returningPreview.positionMs)
        returningPreview.update(PlaybackProgressSnapshot("b", 200L))
        assertEquals(200L, returningPreview.positionMs)
    }

    @Test fun nextTrackResetDoesNotScrollTheOutgoingLyricsToTheTop() {
        val old = LyricWindowPlayback("a")
        old.update(PlaybackProgressSnapshot("a", 90_000L))
        old.update(PlaybackProgressSnapshot("b", 0L))
        assertEquals(90_000L, old.positionMs)
    }

    @Test fun previousTrackAlsoStartsAtZeroWithoutInheritingProgress() {
        val incoming = LyricWindowPlayback("a")
        incoming.update(PlaybackProgressSnapshot("b", 90_000L), previewMs = 100_000L)
        assertEquals(0L, incoming.positionMs)
        incoming.update(PlaybackProgressSnapshot("a", 0L))
        assertEquals(0L, incoming.positionMs)
    }

    @Test fun cachedRapidTargetsStartAtZeroAndOnlyTheCommittedTrackFollowsPlayback() {
        val windows = listOf("b", "c", "d").associateWith(::LyricWindowPlayback)
        windows.values.forEach {
            it.update(PlaybackProgressSnapshot("a", 90_000L))
            assertEquals(0L, it.positionMs)
        }
        windows.values.forEach { it.update(PlaybackProgressSnapshot("d", 500L)) }
        assertEquals(0L, windows.getValue("b").positionMs)
        assertEquals(0L, windows.getValue("c").positionMs)
        assertEquals(500L, windows.getValue("d").positionMs)
    }

    @Test fun rapidReturnToTheStillPlayingTrackPreservesItsAnchor() {
        val window = LyricWindowPlayback("a")
        window.update(PlaybackProgressSnapshot("a", 90_000L))
        assertTrue(window.canReturnTo("a"))
        assertEquals(90_000L, window.positionMs)
    }

    @Test fun restartingAnAlreadyDepartedTrackMustNotReuseItsMiddleAnchor() {
        val window = LyricWindowPlayback("a")
        window.update(PlaybackProgressSnapshot("a", 90_000L))
        assertFalse(window.canReturnTo("b"))
        assertTrue(LyricWindowPlayback("a").canReturnTo("b"))
    }

    @Test fun openingAnExistingTrackAndSeekingStillUseItsActualProgress() {
        val window = LyricWindowPlayback("a")
        window.update(PlaybackProgressSnapshot("a", 90_000L))
        assertEquals(90_000L, window.positionMs)
        window.update(PlaybackProgressSnapshot("a", 90_000L), previewMs = 20_000L)
        assertEquals(20_000L, window.positionMs)
        window.update(PlaybackProgressSnapshot("a", 20_000L))
        assertEquals(20_000L, window.positionMs)
    }
}
