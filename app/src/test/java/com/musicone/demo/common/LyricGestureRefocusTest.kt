package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class LyricGestureRefocusTest {
    @Test fun releaseKeepsARequestEvenWhenPlaybackLineDidNotChange() {
        val request = LyricGestureRefocus()
        request.observe(true, true)
        val revision = request.request()
        request.complete(revision)
        assertTrue(request.pending)
        request.observe(false, true)
        assertTrue(request.pending)
        request.complete(revision)
        assertFalse(request.pending)
    }

    @Test fun anotherGestureCannotBeCompletedByThePreviousAlignment() {
        val request = LyricGestureRefocus()
        request.observe(true, true)
        val old = request.request()
        request.observe(false, true)
        request.observe(true, true)
        request.observe(false, true)
        request.complete(old)
        assertTrue(request.pending)
        request.complete(request.request())
        assertFalse(request.pending)
    }

    @Test fun closingOrLeavingThePageDropsTheOldRequest() {
        val request = LyricGestureRefocus()
        request.observe(true, true)
        request.observe(false, false)
        assertFalse(request.pending)
        request.observe(false, true)
        assertFalse(request.pending)
    }
}
