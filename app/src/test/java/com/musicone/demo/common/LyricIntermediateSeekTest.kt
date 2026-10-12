package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class LyricIntermediateSeekTest {
    private val commit = ProgressLyricSeekCommit("song", 1L, 1L, 80_000L)

    private fun pendingSeek(): LyricIntermediateSeek = LyricIntermediateSeek(0L).apply {
        update(2, seeking = true, intermediate = true, gestureHeld = false,
            enabled = true, request = null, tapLine = null)
        update(2, seeking = false, intermediate = true, gestureHeld = false,
            enabled = true, request = null, tapLine = null, commitLine = 8, commit = commit)
        assertEquals(8, target(2))
    }

    @Test fun backwardDragReplacesThePreviousCommitBeforeItsNotificationClears() {
        val state = pendingSeek()
        state.update(1, seeking = true, intermediate = false, gestureHeld = false,
            enabled = true, request = null, tapLine = null, commitLine = 8, commit = commit)
        assertTrue(state.holding)
        assertEquals(1, state.target(1))
        state.update(0, seeking = true, intermediate = false, gestureHeld = false,
            enabled = true, request = null, tapLine = null)
        assertEquals(0, state.target(0))
        val latest = commit.copy(revision = 2L, positionMs = 10_000L)
        state.update(8, seeking = false, intermediate = false, gestureHeld = false,
            enabled = true, request = null, tapLine = null, commitLine = 1, commit = latest)
        assertEquals(1, state.target(8))
    }

    @Test fun aNewTapCannotBeOverwrittenByTheRetainedCommit() {
        val state = pendingSeek()
        val tap = ProgressLyricSeek("song", 80_000L, .1f, 1L, 1L)
        state.update(8, seeking = false, intermediate = true, gestureHeld = false,
            enabled = true, request = tap, tapLine = 1, commitLine = 8, commit = commit)
        assertEquals(1, state.target(8))
        assertTrue(tap.sourceIsolated.isCompleted)
        state.update(0, seeking = true, intermediate = true, gestureHeld = false,
            enabled = true, request = tap, tapLine = 1)
        assertEquals(0, state.target(0))
        assertTrue(tap.targetReady.isCompleted)
    }
}
