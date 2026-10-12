package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class LyricWindowPlaybackTest {
    @Test fun returningToAHalfPlayedSongCannotReplayItsRetainedProgressSeek() {
        val preview = PlayerSeekPreview()
        val first = LyricWindowPlayback("a")
        first.update(PlaybackProgressSnapshot("a", 0L, 1L))
        val oldSeek = preview.requestProgressLyricSeek("a", 0L, .5f, generation = 1L)
        assertSame(oldSeek, first.progressSeek(oldSeek))
        first.update(PlaybackProgressSnapshot("a", 90_000L, 1L))
        preview.clear()
        assertSame(oldSeek, preview.progressLyricSeek.value)

        val second = LyricWindowPlayback("b")
        second.update(PlaybackProgressSnapshot("b", 0L, 2L))
        assertNull(second.progressSeek(preview.progressLyricSeek.value))
        val returning = LyricWindowPlayback("a")
        returning.update(PlaybackProgressSnapshot("b", 0L, 2L), browsing = true)
        val restarted = PlaybackProgressSnapshot("a", 0L, 3L)
        returning.updateObserved(restarted, null, false, restarted, null, false)
        assertEquals(0L, returning.positionMs)
        assertNull("切回同一首时不能重新执行上一轮的九十秒定位", returning.progressSeek(preview.progressLyricSeek.value))
        returning.update(PlaybackProgressSnapshot("a", 500L, 3L))
        assertEquals(500L, returning.positionMs)
    }

    @Test fun aPreparedUnboundWindowCannotConsumeAProgressSeekFromThePlayingRound() {
        val preview = PlayerSeekPreview()
        val request = preview.requestProgressLyricSeek("a", 0L, .5f, generation = 1L)
        val incoming = LyricWindowPlayback("a")
        incoming.update(PlaybackProgressSnapshot("a", 90_000L, 1L), browsing = true)
        assertEquals(-1L, incoming.generation)
        assertNull(incoming.progressSeek(request))
    }

    @Test fun currentRoundSeeksKeepTheirOriginalRequestAndHandoffSignals() {
        val preview = PlayerSeekPreview()
        val window = LyricWindowPlayback("a")
        window.update(PlaybackProgressSnapshot("a", 40_000L, 3L))
        for (fraction in listOf(.25f, .8f)) {
            val request = preview.requestProgressLyricSeek("a", window.positionMs, fraction, generation = 3L)
            assertSame(request, window.progressSeek(request))
            assertEquals(40_000L, request.fromPositionMs)
            assertFalse(request.sourceIsolated.isCompleted)
            assertFalse(request.targetReady.isCompleted)
        }
        assertNull(window.progressSeek(null))
    }

    @Test fun restartingTheSameSongCannotReplayThePreviousRoundSeek() {
        val preview = PlayerSeekPreview()
        val previousSeek = preview.requestProgressLyricSeek("a", 0L, .5f, generation = 1L)
        val restarted = LyricWindowPlayback("a")
        restarted.update(PlaybackProgressSnapshot("a", 0L, 2L))
        assertNull(restarted.progressSeek(previousSeek))
        val currentSeek = preview.requestProgressLyricSeek("a", 0L, .5f, generation = 2L)
        assertSame(currentSeek, restarted.progressSeek(currentSeek))
    }

    @Test fun anAlreadyBoundZeroWindowCannotBeReusedForAnotherPlaybackRound() {
        val window = LyricWindowPlayback("a")
        val previous = PlaybackProgressSnapshot("a", 0L, 2L)
        window.update(previous)
        assertTrue(window.canReturnTo(previous))
        assertFalse(window.canReturnTo(previous, restarting = true))
        assertFalse(window.canReturnTo(PlaybackProgressSnapshot("a", 0L, 3L)))
        assertTrue(LyricWindowPlayback("a").canReturnTo(previous, restarting = true))
    }

    @Test fun withdrawingPreviewCannotBindTheRestartWindowToALateOldProgressNotification() {
        val observed = PlaybackProgressSnapshot("a", 90_000L, 2L)
        val incoming = LyricWindowPlayback("a")
        incoming.updateObserved(observed, null, true, observed, null, true)
        assertEquals(-1L, incoming.generation)
        // 同曲重新起播已提交，页面收到的上一轮进度通知仍停在九十秒。
        val latest = PlaybackProgressSnapshot("a", 0L, 3L)
        incoming.updateObserved(observed, null, true, latest, null, false)
        assertEquals(3L, incoming.generation)
        assertEquals(0L, incoming.positionMs)
        incoming.updateObserved(latest, 120_000L, false, latest, null, false)
        assertEquals(0L, incoming.positionMs)
        incoming.updateObserved(latest, null, false, latest, null, false)
        assertEquals(0L, incoming.positionMs)
        incoming.updateObserved(observed, null, false, latest, null, false)
        assertEquals(0L, incoming.positionMs)
        incoming.updateObserved(latest, null, false, latest, null, false)
        assertEquals(0L, incoming.positionMs)
        incoming.update(PlaybackProgressSnapshot("a", 500L, 3L))
        assertEquals(500L, incoming.positionMs)
    }

    @Test fun clearedOldSeekPreviewCannotMoveANewTrackBeforeItsNotificationArrives() {
        val incoming = LyricWindowPlayback("new")
        val latest = PlaybackProgressSnapshot("new", 0L, 3L)
        incoming.updateObserved(latest, 120_000L, false, latest, null, false)
        assertEquals(3L, incoming.generation)
        assertEquals(0L, incoming.positionMs)
        incoming.updateObserved(latest, 20_000L, false, latest, 20_000L, false)
        assertEquals(20_000L, incoming.positionMs)
    }

    @Test fun sameSessionSeekKeepsTheObservedFrameUntilTheOriginalNotificationArrives() {
        val window = LyricWindowPlayback("a")
        val observed = PlaybackProgressSnapshot("a", 40_000L, 3L)
        val sought = PlaybackProgressSnapshot("a", 90_000L, 3L)
        window.update(observed)
        window.updateObserved(observed, null, false, sought, null, false)
        assertEquals(40_000L, window.positionMs)
        window.updateObserved(observed, 60_000L, false, sought, 90_000L, false)
        assertEquals(60_000L, window.positionMs)
        window.updateObserved(sought, null, false, sought, null, false)
        assertEquals(90_000L, window.positionMs)
    }

    @Test fun rapidReturnAfterTheImmediateFirstClickPreparesTheRestartAtZero() {
        val firstClick = LyricWindowPlayback("b")
        firstClick.update(PlaybackProgressSnapshot("b", 300L))
        assertFalse(firstClick.canReturnTo(PlaybackProgressSnapshot("b", 0L), restarting = true))
        val returningPreview = LyricWindowPlayback("b")
        returningPreview.update(PlaybackProgressSnapshot("b", 300L), browsing = true)
        assertEquals(0L, returningPreview.positionMs)
        assertTrue(returningPreview.canReturnTo(PlaybackProgressSnapshot("b", 0L), restarting = true))
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
        assertTrue(window.canReturnTo(PlaybackProgressSnapshot("a", 90_000L)))
        assertEquals(90_000L, window.positionMs)
    }

    @Test fun restartingAnAlreadyDepartedTrackMustNotReuseItsMiddleAnchor() {
        val window = LyricWindowPlayback("a")
        window.update(PlaybackProgressSnapshot("a", 90_000L))
        assertFalse(window.canReturnTo(PlaybackProgressSnapshot("b", 0L)))
        assertTrue(LyricWindowPlayback("a").canReturnTo(PlaybackProgressSnapshot("b", 0L)))
    }

    @Test fun replayingTheSameTrackCannotReuseThePreviousPlaybackWindow() {
        val window = LyricWindowPlayback("a")
        window.update(PlaybackProgressSnapshot("a", 90_000L, generation = 2L))
        assertFalse(window.canReturnTo(PlaybackProgressSnapshot("a", 0L, generation = 3L)))
        window.update(PlaybackProgressSnapshot("a", 0L, generation = 3L))
        assertEquals(90_000L, window.positionMs)
        assertEquals(2L, window.generation)
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
