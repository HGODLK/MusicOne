package com.musicone

import org.junit.Assert.*
import org.junit.Test

class LyricLayoutReadinessTest {
    @Test fun sameSizeRemountAndNewPreparationCannotReuseThePreviousGlyphReceipt() {
        val readiness = LyricLayoutReadiness()
        val first = LyricLayoutStamp(Any(), 0, 0, 340, 600, 0, false,
            pane = Any(), mount = 1, preparation = Any())
        readiness.expect(first)
        readiness.drawn(first, false, 340, 600, 320, 320)
        assertTrue(readiness.ready(0, 0))
        for (next in listOf(first.copy(mount = 2), first.copy(preparation = Any()), first.copy(pane = Any()))) {
            readiness.expect(next)
            readiness.drawn(first, false, 340, 600, 320, 320)
            assertFalse(readiness.ready(0, 0))
            readiness.drawn(next, false, 340, 600, 320, 320)
            assertTrue(readiness.ready(0, 0))
        }
    }

    private val owner = Any()
    private val stamp = LyricLayoutStamp(owner, 2, 90, 600, 800, 3, true, 100)

    @Test fun drawnTextMustStillHaveValidLiveGeometryAtTheReleaseFrame() {
        val readiness = LyricLayoutReadiness()
        val plain = stamp.copy(translation = false)
        readiness.expect(plain)
        readiness.drawn(plain, false, 600, 800, 480, 480)
        var geometry: Any? = "first"
        readiness.observeGeometry(plain) { geometry }
        assertEquals("first", readiness.geometry(2, 90))
        geometry = null
        assertNull(readiness.geometry(2, 90))
        geometry = "last"
        assertEquals("last", readiness.geometry(2, 90))
        readiness.clear(owner)
        assertNull(readiness.geometry(2, 90))
    }

    @Test fun matchingLayoutCannotReleaseUntilBothTextClipsCoverTheVisibleGlyphs() {
        val readiness = LyricLayoutReadiness()
        readiness.expect(stamp)
        readiness.drawn(stamp, false, 600, 800, 480, 480, clipReady = false)
        readiness.drawn(stamp, true, 600, 800, 480, 480)
        assertFalse(readiness.ready(2, 90))
        readiness.drawn(stamp, false, 600, 800, 480, 480)
        assertTrue(readiness.ready(2, 90))
        readiness.drawn(stamp, true, 600, 800, 480, 480, clipReady = false)
        assertFalse(readiness.ready(2, 90))
        val next = stamp.copy(generation = 3, rotation = 0)
        readiness.expect(next)
        readiness.drawn(next, false, 600, 800, 480, 480)
        readiness.drawn(next, true, 600, 800, 480, 480, clipReady = false)
        assertFalse(readiness.ready(3, 0))
        readiness.drawn(next, true, 600, 800, 480, 480)
        assertTrue(readiness.ready(3, 0))
    }

    @Test fun originalAndTranslationMustBothUseTheTargetParagraphWidth() {
        val readiness = LyricLayoutReadiness()
        readiness.expect(stamp)
        readiness.drawn(stamp, false, 600, 800, 480, 480)
        assertFalse(readiness.ready(2, 90))
        readiness.drawn(stamp, true, 600, 800, 740, 480)
        assertFalse(readiness.ready(2, 90))
        readiness.drawn(stamp, true, 600, 800, 480, 480)
        assertTrue(readiness.ready(2, 90))
    }

    @Test fun oldViewportCannotConfirmNewTextEvenWhenItsOuterContainerHasBeenPlaced() {
        val readiness = LyricLayoutReadiness()
        val plain = stamp.copy(translation = false)
        readiness.expect(plain)
        readiness.drawn(plain, false, 900, 800, 480, 480)
        assertFalse(readiness.ready(2, 90))
        readiness.drawn(plain, false, 600, 700, 480, 480)
        assertFalse(readiness.ready(2, 90))
        readiness.drawn(plain, false, 600, 800, 480, 480)
        assertTrue(readiness.ready(2, 90))
    }

    @Test fun directionGenerationAndLeadingPaddingInvalidateTheOldProof() {
        val readiness = LyricLayoutReadiness()
        for (target in listOf(stamp.copy(translation = false),
            stamp.copy(generation = 3, rotation = -90, translation = false),
            stamp.copy(generation = 3, rotation = -90, translation = false, leading = 120))) {
            readiness.expect(target)
            assertFalse(readiness.ready(target.generation, target.rotation))
            readiness.drawn(stamp, false, 600, 800, 480, 480)
            if (target != stamp.copy(translation = false)) assertFalse(readiness.ready(target.generation, target.rotation))
            readiness.drawn(target, false, 600, 800, 480, 480)
            assertTrue(readiness.ready(target.generation, target.rotation))
        }
        assertFalse(readiness.ready(2, 90))
    }

    @Test fun lateDisposalOfOldTrackCannotClearTheIncomingTrackProof() {
        val readiness = LyricLayoutReadiness()
        val next = stamp.copy(owner = Any(), translation = false)
        readiness.expect(next)
        readiness.drawn(next, false, 600, 800, 480, 480)
        readiness.clear(owner)
        assertTrue(readiness.ready(2, 90))
        readiness.clear(next.owner)
        assertFalse(readiness.ready(2, 90))
    }
}
