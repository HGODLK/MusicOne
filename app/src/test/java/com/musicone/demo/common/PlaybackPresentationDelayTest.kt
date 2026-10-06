package com.musicone.demo

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPresentationDelayTest {
    @Test fun transientStopIsCancelledByPlaybackRecovery() = runBlocking {
        val values = mutableListOf<Boolean>()
        val key = PlaybackPresentationKey("song", 1)
        val gate = PlaybackPresentationDelay(
            scope = this,
            stillStopped = { true },
            publish = { _, playing -> values += playing },
            delayMs = 30,
        )

        gate.transportStopped(key)
        delay(5)
        assertTrue(values.isEmpty())
        gate.present(key, true)
        delay(40)

        assertEquals(listOf(true), values)
    }

    @Test fun continuousStopPublishesPauseAfterGracePeriod() = runBlocking {
        val values = mutableListOf<Boolean>()
        val key = PlaybackPresentationKey("song", 2)
        val gate = PlaybackPresentationDelay(
            scope = this,
            stillStopped = { it == key },
            publish = { _, playing -> values += playing },
            delayMs = 10,
        )

        gate.transportStopped(key)
        delay(30)

        assertEquals(listOf(false), values)
    }
}
