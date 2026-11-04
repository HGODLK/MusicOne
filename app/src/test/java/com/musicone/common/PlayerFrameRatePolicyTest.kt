package com.musicone

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlayerFrameRatePolicyTest {
    @Test fun touchAndOverlayConditionsAreReadImmediatelyWithoutRecomposition() {
        val policy = PlayerFrameRatePolicy()
        val owner = Any()
        var cover = true
        var pressed = false
        var covered = false
        policy.registerIdle(owner) { cover && !pressed && !covered }
        for (rate in AnimationFrameRate.entries) {
            assertEquals(rate, policy.resolve(rate))
            assertEquals(8, policy.resolveLyrics(rate))
            pressed = true
            assertEquals(rate.fps, policy.resolveLyrics(rate))
            assertEquals(rate, policy.resolve(rate))
            pressed = false
            covered = true
            assertEquals(rate, policy.resolve(rate))
            covered = false
        }
        cover = false
        assertEquals(AnimationFrameRate.FPS_90, policy.resolve(AnimationFrameRate.FPS_90))
        cover = true
        policy.remove(owner)
        assertEquals(AnimationFrameRate.DISPLAY, policy.resolve(AnimationFrameRate.DISPLAY))
    }

    @Test fun seekHoldsConfiguredRateUntilRealAnimationCompletesAndCancellationReleasesIt() = runBlocking {
        val upstream = BroadcastFrameClock()
        val policy = PlayerFrameRatePolicy()
        val owner = Any()
        policy.registerIdle(owner) { true }
        val clock = AnimationFrameClock(upstream, { policy.resolve(AnimationFrameRate.FPS_120).fps })
        val motion = PlayerProgressMotionState(.1f)
        policy.registerBusy(motion) { motion.interacting }
        val seek = motion.beginSeek(.8f)
        val job = launch(clock, start = CoroutineStart.UNDISPATCHED) { motion.animateSeek(seek) }
        assertEquals(AnimationFrameRate.FPS_120, policy.resolve(AnimationFrameRate.FPS_120))
        var frame = 0L
        while (job.isActive && frame < 100) {
            assertEquals(AnimationFrameRate.FPS_120, policy.resolve(AnimationFrameRate.FPS_120))
            upstream.sendFrame(frame++ * 8_333_333L)
            yield()
        }
        assertFalse(job.isActive)
        assertEquals(.8f, motion.value, .001f)
        assertEquals(AnimationFrameRate.FPS_120, policy.resolve(AnimationFrameRate.FPS_120))
        val next = motion.beginSeek(.2f)
        val cancelled = launch(clock, start = CoroutineStart.UNDISPATCHED) { motion.animateSeek(next) }
        assertTrue(motion.interacting)
        cancelled.cancelAndJoin()
        assertFalse(motion.interacting)
        assertEquals(AnimationFrameRate.FPS_120, policy.resolve(AnimationFrameRate.FPS_120))
    }

    @Test fun idleLyricsUseEightAndUiAlwaysFollowsSettings() = runBlocking {
        val upstream = BroadcastFrameClock()
        val policy = PlayerFrameRatePolicy()
        var pressed = false
        policy.registerIdle(this) { !pressed }
        val ui = AnimationFrameClock(upstream, { policy.resolve(AnimationFrameRate.FPS_120).fps })
        val lyrics = AnimationFrameClock(upstream, { policy.resolveLyrics(AnimationFrameRate.FPS_60) }, onFrameAccepted = policy::onLyricFrame)
        val motion = PlayerProgressMotionState(.1f)
        policy.registerBusy(motion) { motion.interacting }
        val advancement = launch(ui, start = CoroutineStart.UNDISPATCHED) { motion.advanceTo(.2f, 1_000) }
        assertFalse(motion.interacting)
        val uiFrames = mutableListOf<Long>()
        val lyricFrames = mutableListOf<Long>()
        val jobs = listOf(launch { while (isActive) ui.withFrameNanos { uiFrames += it } },
            launch { while (isActive) lyrics.withFrameNanos { lyricFrames += it } })
        repeat(120) { frame ->
            if (frame == 60) pressed = true
            yield()
            upstream.sendFrame(frame * 1_000_000_000L / 120)
            yield()
        }
        jobs.forEach { it.cancelAndJoin() }
        advancement.cancelAndJoin()
        assertEquals(120, uiFrames.size)
        assertEquals(34, lyricFrames.size)
        assertTrue(uiFrames.contains(500_000_000L))
        assertTrue(lyricFrames.contains(500_000_000L))
    }
}
