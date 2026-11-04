package com.musicone

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LyricClosedRecordingTest {
    @Test fun sixtyUiDrawsOnlyRecordEightLyricFramesAndInteractionBypassesGate() = runBlocking {
        val policy = PlayerFrameRatePolicy()
        policy.registerIdle(this) { true }
        val upstream = BroadcastFrameClock()
        val clock = AnimationFrameClock(upstream, { policy.resolveLyrics(AnimationFrameRate.FPS_120) },
            onFrameAccepted = policy::onLyricFrame)
        val recording = LyricClosedRecording()
        var count = 0
        val job = launch { while (isActive) clock.withFrameNanos { } }
        repeat(120) { i ->
            yield()
            upstream.sendFrame(i * 1_000_000_000L / 120)
            yield()
            if (i % 2 == 0 && recording.shouldRecord(policy.isIdle, policy.lyricFrameNanos)) count++
        }
        job.cancelAndJoin()
        assertEquals(8, count)
        assertTrue(recording.shouldRecord(false, policy.lyricFrameNanos))
        assertTrue(recording.shouldRecord(false, policy.lyricFrameNanos))
        assertFalse(recording.shouldRecord(true, policy.lyricFrameNanos))
        assertTrue(LyricClosedRecording().shouldRecord(true, policy.lyricFrameNanos))
    }

    @Test fun touchRestoresNextDisplayFrameWithoutWaitingForLowRateDeadline() = runBlocking {
        var pressed = false
        val policy = PlayerFrameRatePolicy()
        policy.registerIdle(this) { !pressed }
        val upstream = BroadcastFrameClock()
        val clock = AnimationFrameClock(upstream, { policy.resolveLyrics(AnimationFrameRate.FPS_120) },
            onFrameAccepted = policy::onLyricFrame)
        val times = mutableListOf<Long>()
        val job = launch { while (isActive) clock.withFrameNanos { times += it } }
        repeat(12) { i ->
            if (i == 10) pressed = true
            yield()
            upstream.sendFrame(i * 1_000_000_000L / 120)
            yield()
        }
        job.cancelAndJoin()
        assertEquals(listOf(0L, 83_333_333L, 91_666_666L), times)
        assertEquals(times.last(), policy.lyricFrameNanos)
    }
}
