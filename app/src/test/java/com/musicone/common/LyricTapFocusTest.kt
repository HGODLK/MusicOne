package com.musicone

import org.junit.Assert.*
import org.junit.Test
import com.musicone.source.sdk.SourceLyricTiming
import com.musicone.source.sdk.SourcePresentation

class LyricTapFocusTest {
    @Test fun oldForwardAndBackwardSamplesCannotDropANewClickedFocus() {
        for (origin in listOf(2_000L, 50_000L)) {
            val focus = LyricTapFocus()
            focus.progressSeek(1)
            focus.request(8, 10_000L, 9_650L, origin)
            focus.observe(origin, origin)
            focus.progressSeek(1)
            assertEquals(8, focus.line)
            focus.observe(9_650L, origin)
            assertEquals(8, focus.line)
            focus.observe(9_650L, 9_650L)
            assertEquals(8, focus.line)
            focus.observe(10_001L, 10_001L)
            assertNull(focus.line)
            focus.request(8, 10_000L, 9_650L, origin)
            focus.progressSeek(2)
            assertNull(focus.line)
        }
    }

    @Test fun pausedPrerollKeepsClickedFocusUntilTheRealLineStarts() {
        val focus = LyricTapFocus()
        focus.request(8, 10_000L, 9_650L)
        repeat(3) { focus.observe(9_650L) }
        assertEquals(8, focus.line)
        focus.observe(9_999L)
        assertEquals(8, focus.line)
        focus.observe(10_000L)
        assertNull(focus.line)
    }

    @Test fun newestClickReplacesThePreviousTarget() {
        val focus = LyricTapFocus()
        focus.request(20, 50_000L, 49_650L)
        focus.request(3, 5_000L, 4_650L)
        focus.observe(4_700L)
        assertEquals(3, focus.line)
        focus.observe(5_000L)
        assertNull(focus.line)
    }

    @Test fun staleFramesCannotReleaseBackwardSeekOrFlashThePreviousLine() {
        val focus = LyricTapFocus()
        focus.request(8, 10_000L, 9_650L)
        focus.observe(9_650L, 50_000L)
        assertEquals(8, focus.line)
        focus.observe(10_001L, 9_999L)
        assertEquals(8, focus.line)
        focus.observe(10_010L, 10_005L)
        assertNull(focus.line)
        focus.request(8, 10_000L, 9_650L)
        focus.observe(9_650L, 2_000L)
        assertEquals(8, focus.line)
    }

    @Test fun anotherSeekOrGestureReleasesTheClickedTarget() {
        val focus = LyricTapFocus()
        focus.request(8, 10_000L, 9_650L)
        focus.observe(2_000L)
        assertNull(focus.line)
        focus.request(8, 10_000L, 9_650L)
        focus.clear()
        assertNull(focus.line)
    }

    @Test fun onlineOffsetPreservesWordTimingAndTranslation() {
        val words = listOf(LyricWord(1000L, 1500L, "测试"))
        val line = TimedLyric(1000L, "测试", translation = "译文", words = words)
        val shifted = offsetOnlineLyricLines(listOf(line), -200L).single()
        assertEquals(800L, shifted.timeMs)
        assertSame(words, shifted.words)
        assertEquals("译文", shifted.translation)
    }

    @Test fun declaredLineAndWordLeadsAreCalculatedIndependently() {
        val baselines = listOf(275L to 105L, 185L to 115L, 565L to 0L, 0L to 0L)
        baselines.forEach { (line, word) ->
            val timing = declaredLyricTiming(SourcePresentation(lineLeadMs = line, wordLeadMs = word))
            assertEquals(10_000L + line, lyricDisplayPosition(10_000L, timing))
            assertEquals(10_000L + word, lyricWordDisplayPosition(10_000L, timing))
        }
        val neteaseTiming = declaredLyricTiming(SourcePresentation(lineLeadMs = 565))
        val lines = listOf(TimedLyric(0, "前一句"), TimedLyric(10_000, "当前句"))
        assertEquals(0, activeLyric(lines, lyricDisplayPosition(9_434, neteaseTiming)))
        assertEquals(1, activeLyric(lines, lyricDisplayPosition(9_435, neteaseTiming)))
        assertEquals(0L, (20_000L * lyricSeekFraction(200L, 20_000L)).toLong())
    }

    @Test fun undeclaredTimingDefaultsToZeroAndTrackDeclarationReplacesPageDefaults() {
        val none = declaredLyricTiming()
        assertEquals(10_000L, lyricDisplayPosition(10_000L, none))
        assertEquals(10_000L, lyricWordDisplayPosition(10_000L, none))
        val overriddenTiming = declaredLyricTiming(SourcePresentation(lineLeadMs = 565, wordLeadMs = 115),
            SourceLyricTiming(40, 15))
        assertEquals(10_040L, lyricDisplayPosition(10_000L, overriddenTiming))
        assertEquals(10_015L, lyricWordDisplayPosition(10_000L, overriddenTiming))
        val manual = declaredLyricTiming(SourcePresentation(lineLeadMs = 565, wordLeadMs = 115),
            SourceLyricTiming(565, 115), libraryTimeline = true)
        assertEquals(10_000L, lyricDisplayPosition(10_000L, manual))
        assertEquals(10_000L, lyricWordDisplayPosition(10_000L, manual))
    }

    @Test fun userLineOffsetIsAppliedOnceAndDoesNotMoveWordClock() {
        val words = listOf(LyricWord(10_000, 10_500, "测试"))
        val original = listOf(TimedLyric(10_000, "测试", words = words))
        val delayed = offsetOnlineLyricLines(original, 250).single()
        val timing = SourceLyricTiming(565, 115)
        assertEquals(10_250L, delayed.timeMs)
        assertSame(words, delayed.words)
        assertEquals(10_565L, lyricDisplayPosition(10_000L, timing))
        assertEquals(10_115L, lyricWordDisplayPosition(10_000L, timing))
        assertEquals(9_685L, delayed.timeMs - timing.lineLeadMs)
        val shifted = listOf(TimedLyric(0, "前一句"), delayed)
        assertEquals(0, activeLyric(shifted, lyricDisplayPosition(9_684, timing)))
        assertEquals(1, activeLyric(shifted, lyricDisplayPosition(9_685, timing)))
    }
}
