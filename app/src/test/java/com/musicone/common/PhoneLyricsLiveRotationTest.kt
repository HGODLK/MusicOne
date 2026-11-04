package com.musicone

import org.junit.Assert.*
import org.junit.Test

class PhoneLyricsLiveRotationTest {
    @Test fun releasingReadingOwnershipForRotationDoesNotKeepTheLyricsFrozen() {
        val anchor = PhoneLyricsReadingAnchor()
        anchor.hold()
        assertTrue(anchor.held)
        anchor.releaseForMotion()
        assertFalse(anchor.held)
        assertNull(anchor.position)
        // 快速反向需要重新捕获当前位置，不能接回上一次的阅读锁。
        anchor.hold()
        assertTrue(anchor.held)
        anchor.release()
        assertFalse(anchor.held)
    }

    @Test fun liveRotationTimeDoesNotAccumulateAReplayDebt() {
        val progress = LyricTurnProgress()
        assertEquals(1_000L, progress.sample(1_000L, 0L, false, "track"))
        assertEquals(1_500L, progress.sample(1_500L, 500_000_000L, false, "track"))
        assertEquals(2_000L, progress.sample(2_000L, 1_000_000_000L, false, "track"))
        assertFalse(progress.catchingUp)
    }
}
