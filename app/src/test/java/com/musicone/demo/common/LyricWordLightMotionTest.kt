package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LyricWordLightMotionTest {
    private val ranges = listOf(LyricHighlightRange(0, 1, LyricWord(100, 400, "光")))

    @Test fun wordSweepMovesRightWithStableEndpoints() {
        var position = 0L
        val motion = LyricWordLightMotion()
        motion.configure(ranges, { position }, true, true, true)
        var previousLeft = .7f
        var previousRight = .7f
        for (time in 0L..500L step 10L) {
            position = time
            motion.advance(.01f, finish = true)
            val left = motion.alpha(0, 0f)
            val right = motion.alpha(0, 1f)
            assertTrue(left in .7f..1f && right in .7f..1f)
            assertTrue(left >= right)
            assertTrue(left >= previousLeft && right >= previousRight)
            if (time in 110L..390L) assertTrue(left > right)
            if (time <= 100L || time >= 400L) assertEquals(left, right, 0f)
            previousLeft = left
            previousRight = right
        }
    }

    @Test fun sweepHasWideSoftEdgeWithGentleSpatialContrast() {
        assertEquals(1f, lyricWordSweepAlpha(.5f, 0f, 1f, true), .00001f)
        assertEquals(.85f, lyricWordSweepAlpha(.5f, .5f, 1f, true), .00001f)
        assertEquals(.7f, lyricWordSweepAlpha(.5f, 1f, 1f, true), .00001f)
        for (position in listOf(0f, .2f, .5f, .8f, 1f)) {
            val samples = (0..100).map { lyricWordSweepAlpha(position, it / 100f, 1f, true) }
            assertTrue(samples.zipWithNext().all { (left, right) -> left >= right })
            assertTrue(samples.count { it > .70001f && it < .99999f } <= 81)
        }
        val left = lyricWordSweepAlpha(.5f, .3f, 1f, true)
        val right = lyricWordSweepAlpha(.5f, .7f, 1f, true)
        assertEquals(.15f, left - right, .00001f)
        assertTrue(left < 1f && right > .7f)
        assertEquals(1f, lyricWordSweepAlpha(.5f, .5f, 0f, true), 0f)
    }

    @Test fun backwardSeekKeepsSpatialLightContinuousAndDisablingClearsIt() {
        var position = 250L
        val motion = LyricWordLightMotion()
        motion.configure(ranges, { position }, true, true, true)
        val left = motion.alpha(0, 0f)
        val right = motion.alpha(0, 1f)
        position = 0L
        assertEquals(left, motion.alpha(0, 0f), 0f)
        assertEquals(right, motion.alpha(0, 1f), 0f)
        motion.advance(1f / 60)
        assertTrue(motion.alpha(0, 0f) <= left)
        assertTrue(motion.alpha(0, 1f) <= right)
        motion.configure(ranges, { position }, false, false, true)
        repeat(120) { motion.advance(1f / 60) }
        assertEquals(1f, motion.alpha(0, 0f), 0f)
        assertEquals(1f, motion.alpha(0, 1f), 0f)
    }

    @Test fun reducedMotionAndUntimedTextKeepUniformBrightness() {
        val motion = LyricWordLightMotion()
        motion.configure(ranges, { 250L }, true, true, false)
        assertEquals(motion.alpha(0, 0f), motion.alpha(0, 1f), 0f)
        motion.configure(listOf(LyricHighlightRange(0, 1, null)), { 250L }, true, true, true)
        assertEquals(1f, motion.alpha(0, 0f), 0f)
        assertEquals(1f, motion.alpha(0, 1f), 0f)
    }

    @Test fun skippedWordSoftensAndConvergesWithoutReplayingOtherWords() {
        var position = 0L
        val motion = LyricWordLightMotion()
        motion.configure(ranges, { position }, true, true, true)
        assertEquals(.7f, motion.alpha(0), .00001f)
        position = 2_000L
        assertEquals(.7f, motion.alpha(0), .00001f)
        motion.advance(1f / 60)
        assertTrue(motion.alpha(0) in .7f..1f)
        assertTrue(motion.alpha(0) < 1f)
        repeat(120) { motion.advance(1f / 60) }
        assertEquals(1f, motion.alpha(0), .00001f)
        assertFalse(motion.running)
    }

    @Test fun backwardSeekPreservesCurrentAppearanceAndReversesSmoothly() {
        var position = 0L
        val motion = LyricWordLightMotion()
        motion.configure(ranges, { position }, true, true, true)
        position = 1_000L
        repeat(6) { motion.advance(1f / 60) }
        val before = motion.alpha(0)
        position = 0L
        assertEquals(before, motion.alpha(0), 0f)
        repeat(120) { motion.advance(1f / 60) }
        assertEquals(.7f, motion.alpha(0), .00001f)
    }

    @Test fun responseUsesElapsedTimeAtDifferentFrameRates() {
        val values = listOf(30, 60, 120).map { fps ->
            var position = 0L
            val motion = LyricWordLightMotion()
            motion.configure(ranges, { position }, true, true, true)
            position = 1_000L
            repeat(fps / 5) { motion.advance(1f / fps) }
            motion.alpha(0)
        }
        values.forEach {
            assertEquals(values[0], it, .00001f)
        }
    }

    @Test fun disablingRemovesBrightnessAndStopsObservingClock() {
        val motion = LyricWordLightMotion()
        motion.configure(ranges, { 0L }, true, false, true)
        assertEquals(.7f, motion.alpha(0), .00001f)
        motion.configure(ranges, { 0L }, true, true, true)
        repeat(60) { motion.advance(1f / 60) }
        motion.configure(ranges, { 0L }, false, false, true)
        repeat(120) { motion.advance(1f / 60) }
        assertEquals(1f, motion.alpha(0), 0f)
        assertFalse(motion.drawing)
        assertNull(motion.observedPosition())
    }

    @Test fun zeroDurationBodySoftensButUntimedPunctuationStaysFixed() {
        var position = 0L
        val motion = LyricWordLightMotion()
        val slices = listOf(LyricHighlightRange(0, 1, LyricWord(100, 100, "光")), LyricHighlightRange(1, 2, null))
        motion.configure(slices, { position }, true, true, true)
        position = 100L
        motion.advance(.016f)
        assertTrue(motion.alpha(0) < 1f)
        assertEquals(1f, motion.alpha(1), 0f)
    }

    @Test fun pausedProgressSettlesAtTheCurrentWordInsteadOfCompletingItsTimeline() {
        var position = 0L
        val motion = LyricWordLightMotion()
        motion.configure(ranges, { position }, true, true, true)
        position = 250L
        repeat(120) { motion.advance(1f / 60) }
        assertEquals(.85f, motion.alpha(0), .00001f)
        assertFalse(motion.running)
    }

    @Test fun outgoingLastWordKeepsBrightnessUntilItsTimelineAndVisualMotionFinish() {
        var position = 200L
        val motion = LyricWordLightMotion()
        motion.configure(ranges, { position }, true, true, true)
        motion.configure(ranges, { position }, true, false, true, allowTail = true)
        assertTrue(motion.finishingTail)
        repeat(30) { motion.advance(1f / 60) }
        assertTrue(motion.finishingTail)
        assertTrue(motion.alpha(0) < 1f)
        position = 400L
        repeat(120) { motion.advance(1f / 60) }
        assertFalse(motion.finishingTail)
        assertEquals(1f, motion.alpha(0), .00001f)
        assertFalse(motion.running)
    }

    @Test fun rapidNextLineSeekAndDisablingDiscardTheOldTail() {
        var position = 200L
        val motion = LyricWordLightMotion()
        motion.configure(ranges, { position }, true, true, true)
        motion.configure(ranges, { position }, true, false, true, allowTail = true)
        assertTrue(motion.finishingTail)
        motion.configure(ranges, { position }, true, false, true, allowTail = false)
        assertFalse(motion.finishingTail)
        motion.configure(ranges, { position }, true, true, true)
        motion.configure(ranges, { position }, true, false, true, allowTail = true)
        position = 0L
        motion.configure(ranges, { position }, true, false, true, allowTail = false)
        assertFalse(motion.finishingTail)
        motion.configure(ranges, { position }, false, false, true, allowTail = true)
        assertFalse(motion.finishingTail)
        repeat(120) { motion.advance(1f / 60) }
    }

    @Test fun veryShortWordAndReducedMotionDoNotHoldTheTail() {
        val short = listOf(LyricHighlightRange(0, 1, LyricWord(100, 120, "光")))
        val motion = LyricWordLightMotion()
        motion.configure(short, { 110L }, true, true, true)
        motion.configure(short, { 110L }, true, false, true, allowTail = true)
        assertFalse(motion.finishingTail)
        motion.configure(ranges, { 200L }, true, true, true)
        motion.configure(ranges, { 200L }, true, false, false, allowTail = true)
        assertFalse(motion.finishingTail)
    }

    @Test fun tailKeepsOutgoingRowReadableThenRestoresPlayedOpacity() {
        var position = 200L
        val row = LyricPlaybackRowMotion(0, 0, 0f)
        row.words.configure(ranges, { position }, true, true, true)
        row.emphasize(1, animate = true, playback = true)
        row.words.configure(ranges, { position }, true, false, true, allowTail = true)
        repeat(120) { row.advance(16_666_667L, false) }
        assertEquals(.72f, row.opacity.value, .001f)
        assertTrue(row.words.finishingTail)
        position = 400L
        repeat(180) { row.advance(16_666_667L, false) }
        assertFalse(row.words.finishingTail)
        assertEquals(.28f, row.opacity.value, .001f)
    }

    @Test fun longOverlappingWordHasABoundedPlaybackTimeTail() {
        var position = 100L
        val longWord = listOf(LyricHighlightRange(0, 1, LyricWord(0, 5000, "光")))
        val motion = LyricWordLightMotion()
        motion.configure(longWord, { position }, true, true, true)
        motion.configure(longWord, { position }, true, false, true, allowTail = true)
        assertTrue(motion.finishingTail)
        position = 1400L
        motion.advance(1f / 60)
        assertFalse(motion.finishingTail)
    }

    @Test fun sharedFrameDriverWakesOnWordClockWithoutReconfiguringTheRow() = runBlocking {
        withTimeout(5_000) {
            val clock = BroadcastFrameClock()
            withContext(clock) {
                var time = 0L
                val position = mutableLongStateOf(0L)
                val motion = LyricPlaybackStepMotion(0) { time }
                motion.configureWords(0, ranges, { position.longValue }, true, true, true)
                val driver = launch(start = CoroutineStart.UNDISPATCHED) { motion.run() }
                try {
                    yield()
                    position.longValue = 1_000L
                    Snapshot.sendApplyNotifications()
                    repeat(120) {
                        yield()
                        time += 16_666_667L
                        clock.sendFrame(time)
                        yield()
                    }
                    assertEquals(1f, motion.row(0).words.alpha(0), .00003f)
                } finally {
                    driver.cancelAndJoin()
                }
            }
        }
    }

    @Test fun rememberedRowRebindsToTheNewClockAfterRelease() = runBlocking {
        withTimeout(5_000) {
            val clock = BroadcastFrameClock()
            withContext(clock) {
                var time = 0L
                val firstPosition = mutableLongStateOf(0L)
                val nextPosition = mutableLongStateOf(0L)
                val motion = LyricPlaybackStepMotion(0) { time }
                val row = motion.row(0)
                motion.retain(0, row)
                motion.configureWords(0, ranges, { firstPosition.longValue }, true, true, true)
                val driver = launch(start = CoroutineStart.UNDISPATCHED) { motion.run() }
                try {
                    suspend fun frames() {
                        repeat(120) {
                            yield()
                            time += 16_666_667L
                            clock.sendFrame(time)
                            yield()
                        }
                    }
                    firstPosition.longValue = 1_000L
                    Snapshot.sendApplyNotifications()
                    frames()
                    assertEquals(1f, row.words.alpha(0), .00003f)

                    motion.release(0, row)
                    motion.retain(0, row)
                    motion.configureWords(0, ranges, { nextPosition.longValue }, true, true, true)
                    frames()
                    assertEquals(.7f, row.words.alpha(0), .00003f)

                    nextPosition.longValue = 1_000L
                    Snapshot.sendApplyNotifications()
                    frames()
                    assertEquals(1f, row.words.alpha(0), .00003f)
                } finally {
                    driver.cancelAndJoin()
                }
            }
        }
    }
}
