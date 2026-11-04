package com.musicone

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerAtmosphereFrameClockTest {
    @Test fun flowStaysAtEightAndCrossfadeFollowsTheGlobalSetting() = runBlocking {
        for (rate in AnimationFrameRate.entries) {
            val upstream = BroadcastFrameClock()
            val clock = PlayerAtmosphereFrameClock(upstream) { rate }
            val frames = mutableListOf<Long>()
            val flow = launch(clock, start = CoroutineStart.UNDISPATCHED) {
                while (isActive) withFrameNanos { frames += it }
            }
            suspend fun second(start: Int) {
                repeat(120) { frame ->
                    upstream.sendFrame((start + frame) * 1_000_000_000L / 120)
                    yield()
                }
            }
            second(0)
            assertEquals(8, frames.size)
            frames.clear()
            val blend = launch(start = CoroutineStart.UNDISPATCHED) { clock.crossfade { awaitCancellation() } }
            second(120)
            assertEquals(if (rate.fps > 0) rate.fps else 120, frames.size)
            blend.cancelAndJoin()
            frames.clear()
            second(240)
            assertEquals(8, frames.size)
            flow.cancelAndJoin()
        }
    }

    @Test fun changesToGlobalCapApplyDuringCrossfadeAndNormalCompletionRestoresEight() = runBlocking {
        val upstream = BroadcastFrameClock()
        var rate = AnimationFrameRate.FPS_120
        val clock = PlayerAtmosphereFrameClock(upstream) { rate }
        val end = CompletableDeferred<Unit>()
        val blend = launch(start = CoroutineStart.UNDISPATCHED) { clock.crossfade { end.await() } }
        var frames = 0
        val flow = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            while (isActive) withFrameNanos { frames++ }
        }
        suspend fun second(start: Int) {
            frames = 0
            repeat(120) { frame ->
                upstream.sendFrame((start + frame) * 1_000_000_000L / 120)
                yield()
            }
        }
        second(0)
        assertEquals(120, frames)
        rate = AnimationFrameRate.FPS_30
        second(120)
        assertEquals(30, frames)
        end.complete(Unit)
        blend.join()
        second(240)
        assertEquals(8, frames)
        flow.cancelAndJoin()
    }
}
