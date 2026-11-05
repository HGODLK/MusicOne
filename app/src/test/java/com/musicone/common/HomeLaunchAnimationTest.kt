package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLaunchAnimationTest {
    private val frame = 16_000_000L

    private fun gate(systemSplash: Boolean) = HomeLaunchGate().also {
        it.prepare(reduceMotion = false, systemSplash = systemSplash)
    }

    @Test
    fun timelineRunsLightHoldThenExit() {
        assertEquals(0f, HomeLaunchTimeline.light(0), 0f)
        assertEquals(1f, HomeLaunchTimeline.light(HomeLaunchTimeline.LightMillis), 0f)
        assertEquals(0f, HomeLaunchTimeline.exit(HomeLaunchTimeline.ExitStartMillis), 0f)
        assertEquals(1f, HomeLaunchTimeline.exit(HomeLaunchTimeline.TotalMillis), 0f)
        assertEquals(1250L, HomeLaunchTimeline.TotalMillis)
    }

    @Test
    fun playsOncePerProcess() {
        val gate = HomeLaunchGate()
        assertTrue(gate.prepare(reduceMotion = false, systemSplash = false))
        assertTrue(gate.takeAnimation())
        assertFalse(gate.takeAnimation())
        assertFalse(gate.prepare(reduceMotion = false, systemSplash = false))
        assertFalse(gate.takeAnimation())
    }

    @Test
    fun warmStartDoesNotWaitForSplash() {
        val gate = gate(systemSplash = true)
        gate.takeAnimation()
        assertFalse(gate.prepare(reduceMotion = false, systemSplash = true))
        assertFalse(gate.awaitingSplash)
    }

    @Test
    fun reduceMotionSkipsAnimation() {
        val gate = HomeLaunchGate()
        assertFalse(gate.prepare(reduceMotion = true, systemSplash = true))
        assertFalse(gate.takeAnimation())
        assertFalse(gate.awaitingSplash)
    }

    @Test
    fun longFrameOnlyAdvancesOneStep() {
        val clock = HomeLaunchClock(gate(systemSplash = false))
        clock.advance(2_000_000_000L)
        assertEquals(34L, clock.elapsedMillis)
    }

    @Test
    fun lightStartsAfterSplashIsRemoved() {
        val gate = gate(systemSplash = true)
        val clock = HomeLaunchClock(gate)
        clock.advance(frame)
        assertEquals(0L, clock.elapsedMillis)
        gate.splashRemoved()
        clock.advance(frame)
        assertEquals(16L, clock.elapsedMillis)
    }

    @Test
    fun startsAnywayWhenNoSystemSplashArrives() {
        val clock = HomeLaunchClock(gate(systemSplash = true))
        repeat(24) { clock.advance(frame) }
        assertEquals(0L, clock.elapsedMillis)
        clock.advance(frame)
        assertEquals(16L, clock.elapsedMillis)
    }

    @Test
    fun splashThemeFollowsAlwaysDark() {
        assertEquals(0, homeLaunchSplashTheme(alwaysDark = false))
        assertEquals(R.style.Theme_MusicOne_Launch_Dark, homeLaunchSplashTheme(alwaysDark = true))
    }
}
