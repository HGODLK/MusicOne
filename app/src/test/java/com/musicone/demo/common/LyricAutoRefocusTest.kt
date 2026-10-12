package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class LyricAutoRefocusTest {
    @Test fun visibleOffsetsConvergeWithoutReversalOrRepeatedAlignment() = runBlocking {
        for (start in listOf(-480f, -80f, 0f, 80f, 480f, 900f)) {
            val clock = BroadcastFrameClock()
            withContext(clock) {
                val refocus = LyricAutoRefocus()
                val playback = LyricPlaybackStepMotion(1)
                var offset = start
                var result: Int? = null
                var previous = abs(offset)
                val operation = launch {
                    result = refocus.run {
                        refocus.settle({ LyricAutoRefocusTarget(4, offset.roundToInt().toFloat()) }, {
                            offset -= it
                            assertTrue(abs(offset) <= previous + 1.01f)
                            previous = abs(offset)
                            it
                        }, { true })
                    }
                    result?.let { playback.consume(it, true) }
                }
                repeat(180) { frame ->
                    yield(); clock.sendFrame((frame + 1L) * 16_666_667L)
                    Snapshot.sendApplyNotifications(); yield()
                }
                assertTrue(operation.isCompleted)
                operation.join()
                assertEquals(4, result)
                assertTrue(abs(offset) <= .51f)
                assertFalse(refocus.active)
                assertEquals(LyricPlaybackStepChange.NONE, playback.consume(4, true))
                assertEquals(LyricPlaybackStepChange.ANIMATE, playback.consume(5, true))
            }
        }
    }

    @Test fun changingVisibleTargetKeepsCurrentPositionAndVelocity() {
        val travel = LyricAutoRefocusTravel()
        var consumed = 0f
        travel.retarget(LyricAutoRefocusTarget(3, 300f))
        repeat(6) { travel.advance(16_666_667, false) { consumed += it; it } }
        val position = travel.motion.value
        val velocity = travel.motion.velocity
        assertTrue(velocity > 0f)
        travel.retarget(LyricAutoRefocusTarget(4, 500f - consumed))
        assertEquals(position, travel.motion.value, 0f)
        assertEquals(velocity, travel.motion.velocity, 0f)
        repeat(180) { travel.advance(33_333_333, false) { consumed += it; it } }
        assertEquals(500f, consumed, .001f)
        assertFalse(travel.running)
    }

    @Test fun livePlaybackChangeCompletesOnlyTheLatestVisibleLine() = runBlocking {
        withContext(BroadcastFrameClock()) {
            val clock = coroutineContext[androidx.compose.runtime.MonotonicFrameClock] as BroadcastFrameClock
            val refocus = LyricAutoRefocus()
            var index = 3
            var applied = 0f
            var result: Int? = null
            val operation = launch {
                result = refocus.run {
                    refocus.settle({ LyricAutoRefocusTarget(index,
                        ((if (index == 3) 300f else 500f) - applied).roundToInt().toFloat()) },
                        { applied += it; it }, { true })
                }
            }
            repeat(180) { frame ->
                if (frame == 6) index = 4
                yield(); clock.sendFrame((frame + 1L) * 16_666_667L); yield()
            }
            operation.join()
            assertEquals(4, result)
            assertEquals(500f, applied, .51f)
        }
    }

    @Test fun unevenFramesUseElapsedTimeAndActualConsumedTravel() {
        for (intervals in listOf(listOf(33_333_333L), listOf(16_666_667L), listOf(11_111_111L),
            listOf(8_333_333L), listOf(16_000_000L, 50_000_000L, 33_000_000L))) {
            val travel = LyricAutoRefocusTravel()
            var applied = 0f
            travel.retarget(LyricAutoRefocusTarget(3, -420f))
            repeat(240) { frame ->
                assertTrue(travel.advance(intervals[frame % intervals.size], false) { applied += it; it })
            }
            assertEquals(-420f, applied, .001f)
            assertFalse(travel.running)
        }
        val blocked = LyricAutoRefocusTravel()
        blocked.retarget(LyricAutoRefocusTarget(3, 420f))
        assertFalse(blocked.advance(100_000_000, false) { 0f })
    }

    @Test fun leavingViewportOrLosingEligibilityDoesNotConfirmOldTarget() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            val refocus = LyricAutoRefocus()
            var target: LyricAutoRefocusTarget? = LyricAutoRefocusTarget(3, 300f)
            var result: Int? = 99
            var scrolls = 0
            val operation = launch {
                result = refocus.run { refocus.settle({ target }, { scrolls++; it }, { true }) }
            }
            yield(); clock.sendFrame(16_666_667); yield()
            target = null
            clock.sendFrame(33_333_334); yield()
            operation.join()
            assertEquals(null, result)
            val before = scrolls
            assertEquals(null, refocus.settle({ LyricAutoRefocusTarget(3, 300f) }, { scrolls++; it }, { false }))
            assertEquals(before, scrolls)
        }
    }

    @Test fun canceledTransactionCannotReleaseOrCompleteItsReplacement() = runBlocking {
        val refocus = LyricAutoRefocus()
        var confirmed = false
        val old = launch { refocus.run { awaitCancellation() }; confirmed = true }
        yield(); assertTrue(refocus.active)
        refocus.cancel()
        val finish = CompletableDeferred<Int>()
        val replacement = launch { refocus.run { finish.await() } }
        yield(); old.join()
        assertTrue(refocus.active)
        assertFalse(confirmed)
        finish.complete(4); replacement.join()
        assertFalse(refocus.active)
    }
}
