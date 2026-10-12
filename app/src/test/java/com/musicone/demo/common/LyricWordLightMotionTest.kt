package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LyricWordLightMotionTest {
    private val ranges = listOf(LyricHighlightRange(0, 1, LyricWord(100, 400, "光")))

    @Test fun skippedWordSoftensAndConvergesWithoutReplayingOtherWords() {
        var position = 0L
        val motion = LyricWordLightMotion()
        motion.configure(ranges, { position }, true, true, true)
        assertEquals(.8f, motion.alpha(0), .00001f)
        assertEquals(.985f, motion.scale(0), .00001f)
        position = 2_000L
        assertEquals(.8f, motion.alpha(0), .00001f)
        motion.advance(1f / 60)
        assertTrue(motion.alpha(0) in .8f..1f)
        assertTrue(motion.alpha(0) < 1f)
        assertTrue(motion.scale(0) > .985f && motion.scale(0) < 1f)
        repeat(120) { motion.advance(1f / 60) }
        assertEquals(1f, motion.alpha(0), .00001f)
        assertEquals(1f, motion.scale(0), .00001f)
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
        assertEquals(.8f, motion.alpha(0), .00001f)
        assertEquals(.985f, motion.scale(0), .00001f)
    }

    @Test fun responseUsesElapsedTimeAtDifferentFrameRates() {
        val values = listOf(30, 60, 120).map { fps ->
            var position = 0L
            val motion = LyricWordLightMotion()
            motion.configure(ranges, { position }, true, true, true)
            position = 1_000L
            repeat(fps / 5) { motion.advance(1f / fps) }
            motion.alpha(0) to motion.scale(0)
        }
        values.forEach {
            assertEquals(values[0].first, it.first, .00001f)
            assertEquals(values[0].second, it.second, .00001f)
        }
    }

    @Test fun scaleStartsGentlyAndCompletesAtItsFixedDuration() {
        var position = 0L
        val motion = LyricWordLightMotion()
        motion.configure(ranges, { position }, true, true, true)
        position = 100L
        repeat(2) { motion.advance(1f / 60) }
        assertTrue(motion.scale(0) in .985f..0.99f)
        repeat(9) { motion.advance(1f / 60) }
        assertTrue(motion.scale(0) < 1f)
        repeat(18) { motion.advance(1f / 60) }
        assertTrue(motion.scale(0) < 1f)
        repeat(2) { motion.advance(1f / 60) }
        assertEquals(1f, motion.scale(0), .00001f)
    }

    @Test fun disablingRemovesBothEffectsAndUnfocusedRowsKeepTheirOriginalScale() {
        val motion = LyricWordLightMotion()
        motion.configure(ranges, { 0L }, true, false, true)
        assertEquals(.8f, motion.alpha(0), .00001f)
        assertEquals(1f, motion.scale(0), 0f)
        motion.configure(ranges, { 0L }, true, true, true)
        repeat(60) { motion.advance(1f / 60) }
        assertEquals(.985f, motion.scale(0), .00001f)
        val before = motion.scale(0)
        motion.configure(ranges, { 0L }, false, false, true)
        assertEquals(before, motion.scale(0), 0f)
        repeat(120) { motion.advance(1f / 60) }
        assertEquals(1f, motion.alpha(0), 0f)
        assertEquals(1f, motion.scale(0), 0f)
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
        assertEquals(1f, motion.scale(1), 0f)
    }

    @Test fun pausedProgressSettlesAtTheCurrentWordInsteadOfCompletingItsTimeline() {
        var position = 0L
        val motion = LyricWordLightMotion()
        motion.configure(ranges, { position }, true, true, true)
        position = 250L
        repeat(120) { motion.advance(1f / 60) }
        assertEquals(.9f, motion.alpha(0), .00001f)
        assertEquals(1f, motion.scale(0), .00001f)
        assertFalse(motion.running)
    }

    @Test fun outgoingLastWordKeepsBrightnessUntilItsTimelineAndVisualMotionFinish() {
        var position = 200L
        val motion = LyricWordLightMotion()
        motion.configure(ranges, { position }, true, true, true)
        val scale = motion.scale(0)
        motion.configure(ranges, { position }, true, false, true, allowTail = true)
        assertTrue(motion.finishingTail)
        assertEquals(scale, motion.scale(0), 0f)
        repeat(30) { motion.advance(1f / 60) }
        assertTrue(motion.finishingTail)
        assertEquals(1f, motion.scale(0), .00001f)
        assertTrue(motion.alpha(0) < 1f)
        position = 400L
        repeat(120) { motion.advance(1f / 60) }
        assertFalse(motion.finishingTail)
        assertEquals(1f, motion.scale(0), .00001f)
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
        assertEquals(1f, motion.scale(0), .00001f)
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
        assertEquals(1f, motion.scale(0), 0f)
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

    @Test fun scaleFinishesAtFixedSpeedRegardlessOfWordDurationOrPausedBrightness() {
        val motions = listOf(400L, 5000L).map { end ->
            var position = 0L
            val motion = LyricWordLightMotion()
            val word = listOf(LyricHighlightRange(0, 1, LyricWord(100, end, "光")))
            motion.configure(word, { position }, true, true, true)
            position = 100L
            motion.advance(.07f)
            assertTrue(motion.scale(0) > .985f && motion.scale(0) < 1f)
            val half = motion.scale(0)
            motion.advance(.10f)
            assertTrue(motion.scale(0) < 1f)
            motion.advance(.30f)
            assertTrue(motion.scale(0) < 1f)
            motion.advance(.05f)
            assertEquals(1f, motion.scale(0), .00001f)
            assertEquals(.8f, motion.alpha(0), .00001f)
            half
        }
        assertEquals(motions[0], motions[1], .00001f)
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
                    assertEquals(1f, motion.row(0).words.alpha(0), .00002f)
                    assertEquals(1f, motion.row(0).words.scale(0), .00001f)
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
                    assertEquals(1f, row.words.alpha(0), .00002f)

                    motion.release(0, row)
                    motion.retain(0, row)
                    motion.configureWords(0, ranges, { nextPosition.longValue }, true, true, true)
                    frames()
                    assertEquals(.8f, row.words.alpha(0), .00002f)

                    nextPosition.longValue = 1_000L
                    Snapshot.sendApplyNotifications()
                    frames()
                    assertEquals(1f, row.words.alpha(0), .00002f)
                } finally {
                    driver.cancelAndJoin()
                }
            }
        }
    }
}
