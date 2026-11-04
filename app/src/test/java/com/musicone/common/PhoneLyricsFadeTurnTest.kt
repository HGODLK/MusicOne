package com.musicone

import org.junit.Assert.*
import org.junit.Test

class PhoneLyricsFadeTurnTest {
    private val landscape = PhoneLyricsFadeFrame(0f, 600f, 48f, 48f, 200f, 1f)
    private val portrait = PhoneLyricsFadeFrame(0f, 1000f, 48f, 48f, 350f, 0f)

    @Test fun landscapeMaskKeepsItsSourceEdgesAndLeavesAlongTheOriginalTurnProgress() {
        val state = PhoneLyricsFadeTurn()
        state.frame(landscape, false, 1f)
        state.capture()
        assertEquals(landscape.atPivot(portrait.pivot), state.frame(portrait, true, 0f))
        // 不均匀进度对应掉帧恢复；遮罩不能随目标朝向直接消失。
        for (p in listOf(.01f, .04f, .38f, .4f, .82f, .98f)) {
            val value = state.frame(portrait, true, p)
            assertEquals(1f - p, value.strength, .0001f)
            assertTrue(value.bottom > value.top)
        }
        assertEquals(portrait, state.frame(portrait, true, 1f))
    }

    @Test fun rapidReverseKeepsTheLastDrawnMaskInsteadOfReplayingAFullMask() {
        val state = PhoneLyricsFadeTurn()
        state.frame(landscape, false, 1f)
        state.capture()
        val midway = state.frame(portrait, true, .63f)
        state.capture()
        assertEquals(midway.atPivot(landscape.pivot), state.frame(landscape, true, 0f))
        assertEquals(landscape, state.frame(landscape, true, 1f))
    }

    @Test fun portraitToLandscapeStartsWithNoMask() {
        val state = PhoneLyricsFadeTurn()
        assertEquals(0f, state.frame(landscape, true, 0f).strength, 0f)
        assertEquals(landscape, state.frame(landscape, true, 1f))
        state.clear()
        assertEquals(portrait, state.frame(portrait, false, 1f))
    }
}
