package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackHistoryTest {
    @Test
    fun previousReturnsActualPlaybackHistory() {
        val history = PlaybackHistory()
        history.remember("a", "c")
        history.remember("c", "b")

        assertEquals("c", history.takePrevious(setOf("a", "b", "c")))
        assertEquals("a", history.takePrevious(setOf("a", "b", "c")))
    }

    @Test
    fun previousSkipsTracksNoLongerInQueue() {
        val history = PlaybackHistory()
        history.remember("removed", "current")

        assertNull(history.takePrevious(setOf("current")))
    }
}
