package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class PlaybackPositionOwnerTest {
    @Test fun restoreWithoutLoadedMediaRetainsProgressOnPollingAndExit() {
        val owner = PlaybackPositionOwner()
        assertEquals(83_000L, owner.position(1L, "qq-song", null, 0L, 83_000L))
        assertFalse(owner.matches(1L, "qq-song", "qq-song"))
        assertEquals(83_000L, owner.position(1L, "qq-song", "qq-song", 0L, 83_000L))
    }

    @Test fun freshMediaTakesOwnershipButOldCallbacksCannotOverwriteNextRequest() {
        val owner = PlaybackPositionOwner()
        owner.attach(1L, "qq-song")
        assertTrue(owner.matches(1L, "qq-song", "qq-song"))
        assertEquals(84_000L, owner.position(1L, "qq-song", "qq-song", 84_000L, 83_000L))
        assertEquals(83_000L, owner.position(2L, "qq-song", "qq-song", 0L, 83_000L))
        assertEquals(12_000L, owner.position(2L, "qq-next", "qq-song", 84_000L, 12_000L))
        owner.attach(2L, "qq-next")
        assertEquals(13_000L, owner.position(2L, "qq-next", "qq-next", 13_000L, 12_000L))
    }
}
