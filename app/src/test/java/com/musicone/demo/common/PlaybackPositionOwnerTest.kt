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

    @Test fun cachedNextTrackCannotAdoptTheFinishedPositionUnderItsNewMediaId() {
        var now = 0L
        val owner = PlaybackPositionOwner { now }
        owner.attach(1, "old")
        assertEquals(240_000L, owner.position(1, "old", "old", 240_000, 239_000))
        owner.attach(2, "cached-next", 0)
        assertTrue(owner.matches(2, "cached-next", "cached-next"))
        assertEquals(0L, owner.position(2, "cached-next", "cached-next", 240_000, 0, playerReady = false))
        now = 30
        assertEquals(0L, owner.position(2, "cached-next", "cached-next", 240_000, 0))
        assertEquals(20L, owner.position(2, "cached-next", "cached-next", 20, 0))
        now = 1_030
        assertEquals(1_020L, owner.position(2, "cached-next", "cached-next", 1_020, 20))
    }

    @Test fun maskedZeroWhileBufferingDoesNotReleaseTheFirstPositionCheck() {
        val owner = PlaybackPositionOwner { 0 }
        owner.attach(2, "cached-next", 0)
        assertEquals(0L, owner.position(2, "cached-next", "cached-next", 0, 0, playerReady = false))
        assertEquals(0L, owner.position(2, "cached-next", "cached-next", 180_000, 0))
        assertEquals(0L, owner.position(2, "cached-next", "cached-next", 0, 0))
    }

    @Test fun immediateAndRepeatedSeeksKeepTheLatestTargetUntilItIsApplied() {
        val owner = PlaybackPositionOwner { 0 }
        owner.attach(2, "cached-next", 0)
        owner.awaitPosition(2, "cached-next", 60_000)
        owner.awaitPosition(2, "cached-next", 120_000)
        assertEquals(120_000L, owner.position(2, "cached-next", "cached-next", 0, 120_000))
        assertEquals(120_000L, owner.position(2, "cached-next", "cached-next", 60_000, 120_000))
        assertEquals(120_000L, owner.position(2, "cached-next", "cached-next", 120_000, 120_000, playerReady = false))
        assertEquals(120_020L, owner.position(2, "cached-next", "cached-next", 120_020, 120_000))
        owner.awaitPosition(2, "cached-next", 30_000)
        assertEquals(30_000L, owner.position(2, "cached-next", "cached-next", 120_020, 30_000))
        assertEquals(30_000L, owner.position(2, "cached-next", "cached-next", 30_000, 30_000))
    }

    @Test fun lateSamplingAfterBackgroundPlaybackAllowsElapsedTimeAndSpeed() {
        var now = 0L
        val owner = PlaybackPositionOwner { now }
        owner.attach(2, "cached-next", 0)
        now = 10_000
        assertEquals(0L, owner.position(2, "cached-next", "cached-next", 180_000, 0, speed = 2f))
        assertEquals(20_000L, owner.position(2, "cached-next", "cached-next", 20_000, 0, speed = 2f))
    }

    @Test fun staleSeekAndSameTrackReloadCannotTransferPositionOwnership() {
        val owner = PlaybackPositionOwner { 0 }
        owner.attach(1, "song", 90_000)
        owner.attach(2, "song", 0)
        owner.awaitPosition(1, "song", 90_000)
        owner.awaitPosition(2, "other", 90_000)
        assertEquals(0L, owner.position(2, "song", "song", 90_000, 0))
        assertEquals(0L, owner.position(2, "song", "song", 0, 0))
    }

    @Test fun durationClampingAndLiveRestoreKeepTheirRealPositions() {
        val owner = PlaybackPositionOwner { 0 }
        owner.attach(2, "song", 90_000)
        assertEquals(60_000L, owner.position(2, "song", "song", 60_000, 90_000, durationMs = 60_000))
        owner.attach(3, "song")
        assertEquals(83_000L, owner.position(3, "song", "song", 83_000, 0, playerReady = false))
    }
}
