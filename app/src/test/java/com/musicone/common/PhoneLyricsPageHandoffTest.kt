package com.musicone

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PhoneLyricsPageHandoffTest {
    @Test fun wholeLyricsGroupFollowsThePageCenterInBothPhoneOrientations() = runBlocking {
        for (rotation in listOf(0, 90, -90)) {
            val motion = PageMotion(this, true, 300, 300)
            motion.updateHost(Rect(0f, 0f, 900f, 2000f))
            motion.sources["surface"] = MotionAnchor(Rect(20f, 1800f, 880f, 1960f))
            motion.targets["surface"] = MotionAnchor(Rect(0f, 0f, 900f, 2000f))
            assertEquals(Offset.Zero, phoneLyricsPageOffset(motion, null, rotation))
            assertTrue(motion.beginDrag())
            motion.dragTo(.5f)
            assertEquals(Offset(0f, 440f), phoneLyricsPageOffset(motion, null, rotation))
            assertTrue(phoneLyricsPageHeld(motion.phase, motion.value))
            motion.dragTo(1f)
            assertEquals(Offset.Zero, phoneLyricsPageOffset(motion, null, rotation))
            assertFalse(phoneLyricsPageHeld(motion.phase, motion.value))
        }
        assertFalse(phoneLyricsPageHeld(MotionPhase.MOVING, .5f))
    }
}
