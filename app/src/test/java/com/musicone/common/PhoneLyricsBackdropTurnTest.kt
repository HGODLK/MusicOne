package com.musicone

import org.junit.Assert.*
import org.junit.Test

class PhoneLyricsBackdropTurnTest {
    private val portrait = PhoneLyricsBackdropFrame(100f, 240f, .6f, 700f, 820f, 400f)
    private val landscape = PhoneLyricsBackdropFrame(0f, 0f, 0f, 450f, 550f, 200f)

    @Test fun retainedBlurKeepsBothSidesOutsideTheLiveFocusWithoutChangingItsProfile() {
        for (pivot in listOf(200f, 600f)) {
            val state = PhoneLyricsBackdropTurn()
            state.frame(portrait, false, false, 1f)
            state.capture()
            val target = landscape.copy(pivot = pivot)
            val source = portrait.atPivot(pivot)
            for (p in listOf(0f, .03f, .38f, .4f, .82f, .99f, 1f)) {
                val focus = LyricBoundaryEdges(pivot - 190f, pivot + 330f)
                val frame = state.frame(target, true, true, p, focus, 8f)
                assertTrue(frame.topEnd <= focus.top - 8f)
                assertTrue(frame.bottomStart >= focus.bottom + 8f)
                assertEquals(source.topEnd - source.top, frame.topEnd - frame.top, .001f)
                assertEquals(source.bottomEnd - source.bottomStart, frame.bottomEnd - frame.bottomStart, .001f)
                assertEquals(source.peak, frame.peak, 0f)
                assertEquals(1f - p, frame.strength, .0001f)
            }
            val protected = state.frame(target, true, true, .45f, LyricBoundaryEdges(pivot - 190f, pivot + 330f), 8f)
            state.capture()
            assertEquals(protected.atPivot(portrait.pivot), state.frame(portrait, false, true, 0f))
        }
    }

    @Test fun focusProtectionPreservesNonOverlappingMasksAndStableLayouts() {
        val focus = LyricBoundaryEdges(260f, 650f)
        assertEquals(portrait, portrait.protectFocus(focus, 8f))
        val state = PhoneLyricsBackdropTurn()
        assertEquals(portrait, state.frame(portrait, false, false, 1f, focus, 8f))
    }

    @Test fun bothLandscapeDirectionsStartWithTheSourceBoundaryAndFadeToClearLyrics() {
        for (targetPivot in listOf(200f, 600f)) {
            val state = PhoneLyricsBackdropTurn()
            state.frame(portrait, false, false, 1f)
            state.capture()
            assertTrue(state.retained)
            val target = landscape.copy(pivot = targetPivot)
            val source = portrait.atPivot(targetPivot)
            assertEquals(source, state.frame(target, true, true, 0f))
            assertEquals(source.copy(strength = .5f), state.frame(target, true, true, .5f))
            assertEquals(source.copy(strength = 0f), state.frame(target, true, true, 1f))
            assertEquals(0f, state.frame(target, true, false, 1f).strength, 0f)
            assertFalse(state.retained)
        }
    }

    @Test fun reversingMidTurnPreservesTheCurrentBoundaryAndStrength() {
        val state = PhoneLyricsBackdropTurn()
        state.frame(portrait, false, false, 1f)
        state.capture()
        val interrupted = state.frame(landscape, true, true, .4f)
        state.capture()
        assertEquals(interrupted.atPivot(portrait.pivot), state.frame(portrait, false, true, 0f))
        val middle = state.frame(portrait, false, true, .5f)
        assertEquals(.8f, middle.strength, .0001f)
        assertEquals(portrait, state.frame(portrait, false, true, 1f))
    }

    @Test fun settledLandscapeAndLandscapeToPortraitKeepTheirExistingEndpoints() {
        val state = PhoneLyricsBackdropTurn()
        state.frame(landscape, true, false, 1f)
        state.capture()
        assertFalse(state.retained)
        assertEquals(0f, state.frame(landscape, true, true, 0f).strength, 0f)
        assertEquals(portrait, state.frame(portrait, false, true, 0f))
        state.clear()
        state.capture()
        assertFalse(state.retained)
    }

    @Test fun clearAndBlurredWeightsRemainComplementaryThroughoutTheFade() {
        for (strength in listOf(0f, .1f, .5f, .9f, 1f)) {
            for (clear in listOf(0f, .06f, .4f, .78f, 1f)) {
                val blurred = 1f - clear
                assertEquals(1f, lyricBackdropClearAlpha(clear, strength) + blurred * strength, .0001f)
            }
        }
        assertEquals(1f, lyricBackdropClearAlpha(LYRIC_BACKDROP_FALLBACK_ALPHA, 0f), 0f)
        assertEquals(LYRIC_BACKDROP_FALLBACK_ALPHA,
            lyricBackdropClearAlpha(LYRIC_BACKDROP_FALLBACK_ALPHA, 1f), .0001f)
    }
}
