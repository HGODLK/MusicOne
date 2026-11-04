package com.musicone

import org.junit.Assert.*
import org.junit.Test

class SourcePlaybackBehaviorTest {
    @Test fun renewalBudgetIsDeclaredPerTrackAndResetForANewAttempt() {
        val track = MusicTrack("custom-1", MusicSource.fromId("test.source"), "歌曲", "歌手", "", 1000, 0, 0, "", "")
        val recovery = PlaybackFailureRecovery()
        assertFalse(recovery.mayRenew(track, limit = 0))
        assertTrue(recovery.mayRenew(track, limit = 1))
        assertFalse(recovery.mayRenew(track, limit = 1))
        assertTrue(recovery.mayRenew(track.copy(id = "custom-2"), limit = 1))
        recovery.reset()
        assertTrue(recovery.mayRenew(track, limit = 1))
    }
}
