package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.junit.Test

class PlayerProgressHandoffTest {
    @Test fun naturalHandoffDoesNotReturnToTheFinishedTrackWhenItsNotificationIsLate() = runBlocking {
        val old = MusicOneUiState(currentTrack = track("old"))
        val notifications = MutableStateFlow(old)
        var latest = old
        val playback = object : StateFlow<MusicOneUiState> by notifications {
            override val value get() = latest
        }
        val progress = MutableStateFlow(PlaybackProgressSnapshot("old", 240_000))
        val activity = MutableStateFlow(PlaybackActivity("old", false))
        val preview = MutableStateFlow<RapidTrackSwitchPresentation?>(null)
        val snapshots = mutableListOf<PlayerTimelineSnapshot>()
        val collector = launch { playerTimelineSnapshots(playback, progress, activity, preview).collect { snapshots += it } }
        try {
            repeat(10) { yield() }
            preview.value = RapidTrackSwitchPresentation(track("new"), TrackTransitionDirection.NEXT,
                RapidTrackSwitchPhase.SETTLING, true, 1)
            repeat(10) { yield() }
            val handoff = PlayerProgressHandoff("old", true)
            assertEquals(PlayerProgressHandoffAction.RESET, handoff.update("old", true, true))
            latest = old.copy(currentTrack = track("new"))
            preview.value = null
            repeat(10) { yield() }
            val committed = snapshots.last()
            assertEquals("new", committed.trackId)
            assertEquals(0L, committed.positionMs)
            assertFalse(committed.advancing)
            assertEquals(PlayerProgressHandoffAction.NONE,
                handoff.update(committed.trackId, committed.switching, true))
            notifications.value = latest
            progress.value = PlaybackProgressSnapshot("new", 1_000)
            activity.value = PlaybackActivity("new", true)
            repeat(10) { yield() }
            assertEquals(1_000L, snapshots.last().positionMs)
        } finally { collector.cancelAndJoin() }
    }

    @Test fun samplingAndPlaybackUpdatesCannotReplayTheSameReset() {
        val handoff = PlayerProgressHandoff("old", true)
        assertEquals(PlayerProgressHandoffAction.RESET, handoff.update("next", false, true))
        repeat(12) { assertEquals(PlayerProgressHandoffAction.NONE, handoff.update("next", false, true)) }
        assertEquals(PlayerProgressHandoffAction.RESET, handoff.update("next", true, true))
        assertEquals(PlayerProgressHandoffAction.NONE, handoff.update("other", true, true))
        assertEquals(PlayerProgressHandoffAction.NONE, handoff.update("other", false, true))
    }

    @Test fun cachedNaturalTransitionPublishesZeroInsteadOfAStaleNewTrackPosition() = runBlocking {
        var now = 0L
        val owner = PlaybackPositionOwner { now }
        owner.attach(1, "old")
        val playback = MutableStateFlow(MusicOneUiState(currentTrack = track("old")))
        val progress = MutableStateFlow(PlaybackProgressSnapshot("old", 240_000))
        val activity = MutableStateFlow(PlaybackActivity("old", true))
        val preview = MutableStateFlow<RapidTrackSwitchPresentation?>(null)
        val snapshots = mutableListOf<PlayerTimelineSnapshot>()
        val collector = launch { playerTimelineSnapshots(playback, progress, activity, preview).collect { snapshots += it } }
        try {
            repeat(10) { yield() }
            owner.attach(2, "cached-next", 0)
            playback.value = playback.value.copy(currentTrack = track("cached-next"))
            progress.value = PlaybackProgressSnapshot("cached-next", 0)
            activity.value = PlaybackActivity("cached-next", false)
            repeat(10) { yield() }
            val newTrackStart = snapshots.size - 1
            for (ready in listOf(false, true)) {
                now += 10
                progress.value = PlaybackProgressSnapshot("cached-next",
                    owner.position(2, "cached-next", "cached-next", 240_000, progress.value.positionMs, ready))
                activity.value = PlaybackActivity("cached-next", ready)
                repeat(10) { yield() }
            }
            assertTrue(snapshots.drop(newTrackStart).all { it.trackId == "cached-next" && it.positionMs == 0L })
            progress.value = PlaybackProgressSnapshot("cached-next",
                owner.position(2, "cached-next", "cached-next", 20, progress.value.positionMs))
            repeat(10) { yield() }
            assertEquals(20L, snapshots.last().positionMs)
        } finally { collector.cancelAndJoin() }
    }

    @Test fun genuineCancellationReturnsToOriginAndHiddenResumeOnlySynchronizes() {
        val handoff = PlayerProgressHandoff("old", true)
        handoff.update("old", true, true)
        assertEquals(PlayerProgressHandoffAction.RETURN_TO_ORIGIN, handoff.update("old", false, true))
        assertEquals(PlayerProgressHandoffAction.SYNC, handoff.update("new", false, false))
        assertEquals(PlayerProgressHandoffAction.SYNC, handoff.update("new", false, true))
    }

    @Test fun immediateSeekInterruptsResetAndContinuesFromTheTarget() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            val motion = PlayerProgressMotionState(1f)
            motion.requestReset(this)
            var time = 0L
            suspend fun frames(count: Int) { repeat(count) {
                yield(); time += 16_666_667; clock.sendFrame(time); yield()
            } }
            frames(4)
            val before = motion.value
            val revision = motion.beginSeek(.65f)
            val seek = launch { motion.animateSeek(revision) }
            frames(25)
            seek.join()
            assertTrue(before > 0f && before < 1f)
            assertEquals(.65f, motion.value, .0001f)
            motion.awaitReset()
            assertEquals(.65f, motion.value, .0001f)
            val advance = launch { motion.advanceTo(.66f, 1_000) }
            frames(70)
            advance.join()
            assertEquals(.66f, motion.value, .0001f)
        }
    }

    private fun track(id: String) = MusicTrack(id, MusicSource.QQ, id, "歌手", "", 240_000, 0, 0, "", "")
}
