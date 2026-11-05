package com.musicone

import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import org.junit.Assert.*
import org.junit.Test

class AiChatFollowMotionTest {
    private val spec = spring<Float>(dampingRatio = 1f, stiffness = 240f, visibilityThreshold = .5f)
    private val frame = 16_666_667L

    @Test fun initialCorrectionAcceleratesInsteadOfJumpingAndSettlesAtEveryFrameRate() {
        listOf(30, 60, 90, 120).forEach { fps ->
            val motion = AiChatFollowMotion()
            val deltaTime = 1_000_000_000L / fps
            var remaining = 900f
            val first = motion.advance(remaining, deltaTime, spec)
            assertTrue("$fps: $first", first > 0f && first < remaining * .15f)
            remaining -= first
            repeat(fps * 3) {
                val delta = motion.advance(remaining, deltaTime, spec)
                assertTrue(delta >= 0f && delta <= remaining)
                remaining -= delta
            }
            assertEquals("$fps", 0f, remaining, .5f)
            assertEquals(0f, motion.velocity, .5f)
        }
    }

    @Test fun streamingGrowthContinuesCurrentVelocityInsteadOfRestartingFromRest() {
        val motion = AiChatFollowMotion()
        var remaining = 300f
        repeat(6) { remaining -= motion.advance(remaining, frame, spec) }
        val velocityBefore = motion.velocity
        val grown = remaining + 160f
        val continued = motion.advance(grown, frame, spec)
        val restarted = AiChatFollowMotion().advance(grown, frame, spec)
        assertTrue(velocityBefore > 0f)
        assertTrue("$continued <= $restarted", continued > restarted * 2f)
        assertTrue(motion.velocity > velocityBefore * .5f)
        assertTrue(continued < grown * .5f)
    }

    @Test fun continuousGrowthAndLateCardHeightConvergeAfterTheStreamStops() {
        val motion = AiChatFollowMotion()
        var remaining = 0f
        repeat(90) { index ->
            remaining += if (index == 50) 700f else 12f
            val delta = motion.advance(remaining, frame, spec)
            assertTrue(delta in 0f..remaining)
            if (index == 50) assertTrue(delta < 700f)
            remaining -= delta
        }
        repeat(180) { remaining -= motion.advance(remaining, frame, spec) }
        assertEquals(0f, remaining, .5f)
    }

    @Test fun shrinkingTargetNeverOvershootsOrScrollsBackward() {
        val motion = AiChatFollowMotion()
        repeat(10) { motion.advance(800f, frame, spec) }
        assertEquals(2f, motion.advance(2f, frame, spec), .001f)
        assertEquals(0f, motion.velocity, .001f)
        assertEquals(0f, motion.advance(-20f, frame, spec), 0f)
    }

    @Test fun reducedMotionUsesTheSharedSnapPolicy() {
        val motion = AiChatFollowMotion()
        assertEquals(128f, motion.advance(128f, frame, snap()), 0f)
        assertEquals(0f, motion.velocity, 0f)
    }

    @Test fun upwardReadingAndHistoryPrependingDisableFollowUntilExplicitReturnToTail() {
        val intent = AiChatFollowIntent()
        assertTrue(intent.following)
        intent.userScroll(1f)
        intent.reachedTail(true)
        assertFalse(intent.following)
        intent.userScroll(-10f)
        intent.reachedTail(false)
        assertFalse(intent.following)
        intent.reachedTail(true)
        assertTrue(intent.following)
        intent.readEarlier()
        intent.reachedTail(true)
        assertFalse(intent.following)
    }
}
