package com.musicone.demo

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaylistArtworkSynchronizationTest {
    @Test fun lateArtworkWaitsForNextReturnAndFullInformationFade() = runBlocking {
        val sync = PlaylistArtworkSynchronization()
        sync.holdPlayback("a")
        delay(320)
        assertTrue(sync.held("a"))
        val release = launch { sync.returned() }
        delay(60)
        assertTrue(sync.held("a"))
        release.join()
        assertFalse(sync.held("a"))
    }

    @Test fun anotherPlayOrInterruptedReturnCannotReleaseTheNewTransaction() = runBlocking {
        val sync = PlaylistArtworkSynchronization()
        sync.holdPlayback("a")
        var release = launch { sync.returned() }
        delay(30); sync.holdPlayback("b")
        release.join()
        assertTrue(sync.held("a")); assertTrue(sync.held("b"))
        release = launch { sync.returned() }
        delay(30); release.cancelAndJoin()
        assertTrue(sync.held("b"))
        sync.returned()
        assertFalse(sync.held("a")); assertFalse(sync.held("b"))
    }
}
