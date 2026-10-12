package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GestureReleaseVelocityTest {
    @Test fun highRefreshRateTinyContinuousMovesKeepTheirActualMomentum() {
        for (density in listOf(1f, 3f)) {
            val release = GestureReleaseVelocity(0L, density, stablePlayerSettlement = true)
            for (time in 8L..200L step 8L) release.moved(time, .4f * density)
            assertEquals(50f, release.settlingVelocity(201L, 0f, true) / density, .001f)
            assertEquals(0f, release.settlingVelocity(400L, 0f, true), 0f)
        }
    }

    @Test fun finalLiftNoiseIsRejectedButRealReversalAndOrdinaryCallersRemainAvailable() {
        val release = GestureReleaseVelocity(0L, stablePlayerSettlement = true)
        release.moved(20L, 20f)
        assertEquals(0f, release.finalDelta(30L, -2f), 0f)
        assertEquals(0f, release.finalDelta(30L, 100f), 0f)
        assertEquals(10f, release.finalDelta(30L, 10f), 0f)
        assertEquals(-16f, release.finalDelta(40L, -16f), 0f)
        assertEquals(-2f, GestureReleaseVelocity(0L).finalDelta(30L, -2f), 0f)
    }

    @Test fun playerSettlementRejectsLiftSpikesWithoutChangingIntentOrOtherCallers() {
        val player = GestureReleaseVelocity(0L, stablePlayerSettlement = true)
        val ordinary = GestureReleaseVelocity(0L)
        listOf(player, ordinary).forEach { release ->
            release.moved(20L, 20f)
            release.moved(40L, 20f)
            release.moved(60L, 20f)
        }
        assertEquals(1000f, player.settlingVelocity(65L, 7000f, true), .001f)
        assertEquals(7000f, player.released(65L, 7000f, true), .001f)
        assertEquals(7000f, ordinary.settlingVelocity(65L, 7000f, true), .001f)
    }

    @Test fun playerSettlementKeepsSparseMomentumAndDistinguishesNoiseFromRealReversal() {
        listOf(1f, 2f, 3f).forEach { density ->
            val release = GestureReleaseVelocity(0L, density, stablePlayerSettlement = true)
            release.moved(20L, -32f * density)
            release.moved(30L, 3f * density)
            assertEquals(-1600f, release.settlingVelocity(35L, 500f * density, true) / density, .001f)
            assertEquals(0f, release.settlingVelocity(180L, -2000f * density, true), 0f)
            release.moved(200L, 12f * density)
            assertTrue(release.settlingVelocity(205L, -2000f * density, true) > 0f)
            assertEquals(0f, release.settlingVelocity(205L, 2000f * density, false), 0f)
        }
    }

    @Test fun playerSettlementRespondsToRecentDeceleration() {
        val release = GestureReleaseVelocity(0L, stablePlayerSettlement = true)
        release.moved(20L, 40f)
        release.moved(40L, 20f)
        release.moved(60L, 10f)
        release.moved(80L, 5f)
        assertTrue(release.settlingVelocity(85L, 8000f, true) in 250f..1000f)
    }

    @Test fun stoppedFingerAndCancelledGestureDiscardResidualVelocity() {
        val release = GestureReleaseVelocity(100)
        release.moved(120, 8f)
        assertEquals(0f, release.released(250, 1500f, true), 0f)
        assertEquals(0f, release.released(140, 1500f, false), 0f)
        assertEquals(1500f, release.released(140, 1500f, true), 0f)
    }

    @Test fun liftNoiseCannotReverseTheLastMeaningfulDirection() {
        val release = GestureReleaseVelocity(100)
        release.moved(120, 10f)
        release.moved(130, -.2f)
        assertEquals(500f, release.released(140, -30f, true), 0f)
        release.moved(145, -8f)
        assertEquals(-1200f, release.released(160, -1200f, true), 0f)
    }

    @Test fun sparseFastSwipeAndDensityKeepTheSameIntent() {
        listOf(1f, 2f, 3f).forEach { density ->
            val release = GestureReleaseVelocity(100, density)
            release.moved(120, -32f * density)
            val velocity = release.released(130, 0f, true)
            assertEquals(-1600f, velocity / density, .001f)
            assertTrue(shouldOpenPlayer(.02f, velocity / density, true, -32f))
            assertTrue(shouldDismissPlayer(.98f, -velocity / density, true, 32f))
        }
    }

    @Test fun pauseDecaysAndMeaningfulReversalUsesTheNewDirection() {
        val release = GestureReleaseVelocity(100)
        release.moved(120, 24f)
        assertEquals(1200f, release.released(140, 1200f, true), 0f)
        assertTrue(release.released(180, 1200f, true) in 0f..900f)
        assertEquals(0f, release.released(230, 1200f, true), 0f)
        release.moved(260, -30f)
        assertTrue(release.released(270, 0f, true) < 0f)
    }

    @Test fun releaseAnimationKeepsShortPauseMomentumWithoutKeepingStoppedFingerMomentum() {
        val release = GestureReleaseVelocity(100)
        release.moved(120, 24f)
        val intent = release.released(180, 1200f, true)
        val settling = release.settlingVelocity(180, 1200f, true)
        assertTrue(settling > intent)
        assertEquals(1200f, settling, .001f)
        assertEquals(0f, release.settlingVelocity(280, 1200f, true), 0f)
        assertEquals(0f, release.settlingVelocity(140, 1200f, false), 0f)
    }

    @Test fun ordinaryPageDragsCommitBeforeCrossingTheFullScreenProgressThreshold() {
        // 播放页慢拖过半屏（0.45f 展开度即下拉 0.55f）正常提交
        assertTrue(shouldDismissPlayer(.45f, 0f, true, 36f))
        // 播放页慢拖未过半屏（0.9f 展开度即下拉 0.1f）即使有位移也不误提交
        assertFalse(shouldDismissPlayer(.9f, 0f, true, 36f))
        // 播放页短划必须满足 32dp 与 650dp/s
        assertTrue(shouldDismissPlayer(.9f, 700f, true, 32f))
        assertFalse(shouldDismissPlayer(.99f, 240f, true, 10f))
        // 歌词过中线（0.6f >= 0.5f）慢拖提交；未过中线（0.4f < 0.5f）慢拖不提交
        assertTrue(lyricsDragTarget(.6f, 0f, -32f))
        assertFalse(lyricsDragTarget(.4f, 0f, -32f))
        // 歌词短划满足 24dp 与 500dp/s
        assertTrue(lyricsDragTarget(.1f, -500f, -24f))
        assertFalse(lyricsDragTarget(.1f, -400f, -20f))
    }

    @Test fun intentDistinguishesSlowDragNoiseReversalAndCancellation() {
        assertFalse(shouldOpenPlayer(.03f, -1500f, true, -5f))
        assertFalse(shouldOpenPlayer(.2f, -200f, true, -24f))
        assertTrue(shouldOpenPlayer(.6f, 0f, true, -120f))
        assertTrue(shouldOpenPlayer(.6f, 1500f, true, -120f))
        assertFalse(shouldOpenPlayer(.6f, -1500f, false, -120f))
        assertFalse(gestureTarget(.2f, 0f, 40f, false))
        assertTrue(gestureTarget(.2f, 1200f, 40f, false))
        assertFalse(gestureTarget(.8f, -1200f, 40f, false))
    }

    @Test fun shortFastSwipeSurvivesSparseSamplingAndBriefLiftPause() {
        val release = GestureReleaseVelocity(100)
        release.moved(115, -32f)
        assertTrue(release.released(135, -100f, true) <= -650f)
        assertTrue(shouldOpenPlayer(.01f, release.released(135, -100f, true), true, -32f))
        assertFalse(shouldOpenPlayer(.01f, -3000f, true, -6f))
        assertFalse(shouldOpenPlayer(.01f, -230f, true, -10f))
        assertEquals(0f, release.released(230, -3000f, true), 0f)
    }

    @Test fun clearShortSwipeAndLongDragDoNotBounceAfterLift() {
        assertTrue(shouldOpenPlayer(.02f, -700f, true, -32f))
        assertTrue(shouldDismissPlayer(.98f, 700f, true, 32f))
        assertTrue(lyricsDragTarget(.03f, -500f, -24f))
        assertTrue(gestureTarget(.95f, -500f, 80f, false,
            dragDistanceDp = 60f, flingVelocityDp = 240f))
        assertFalse(shouldOpenPlayer(.02f, -1000f, true, -6f))
    }

    @Test fun diagonalFirstSamplesKeepListeningForVerticalIntent() {
        assertEquals(0, verticalSlopIntent(9f, 8f, 8f))
        assertEquals(1, verticalSlopIntent(10f, 15f, 8f))
        assertEquals(0, verticalSlopIntent(10f, 11f, 8f))
        assertEquals(-1, verticalSlopIntent(30f, 8f, 8f))
    }

    @Test fun liftHookPreservesFastShortSwipeEvenWhenNetDistanceShrinks() {
        listOf(1f, 2f, 3f).forEach { density ->
            val release = GestureReleaseVelocity(100, density)
            release.moved(115, -34f * density)
            release.moved(125, 6f * density)
            val velocity = release.released(130, 700f * density, true)
            assertTrue(velocity / density < -900f)
            assertEquals(-34f, release.effectiveDistancePx / density, .001f)
            assertTrue(shouldOpenPlayer(.01f, velocity / density, true, release.effectiveDistancePx / density))
        }
    }

    @Test fun cumulativeDeliberateReverseChangesIntentButStoppedHookDoesNotFling() {
        val release = GestureReleaseVelocity(100)
        release.moved(120, -40f)
        release.moved(122, 3f)
        release.moved(124, 3f)
        assertTrue(release.released(125, 600f, true) < 0f)
        release.moved(126, 3f)
        val velocity = release.released(130, -1000f, true)
        assertTrue(velocity > 0f)
        assertTrue(shouldOpenPlayer(.6f, velocity, true, release.effectiveDistancePx))
        assertFalse(shouldOpenPlayer(.2f, velocity, true, release.effectiveDistancePx, 32f))
        assertEquals(0f, release.released(260, 1000f, true), 0f)
    }

    @Test fun playerDragReleaseAndReversalKeepPositionAndVelocity() = runBlocking {
        val clock = BroadcastFrameClock()
        val motion = PageMotion(CoroutineScope(coroutineContext + clock), true, 320, 280,
            PlayerReturnAnimation, PlayerEnterAnimation, musicSpring(stiffness = 320f),
            immediateGestureHandoff = true)
        var time = 0L
        suspend fun frames(count: Int) {
            repeat(count) { yield(); time += 16_666_667L; clock.sendFrame(time); yield() }
        }
        assertTrue(motion.beginDrag())
        motion.dragTo(.6f)
        motion.releaseDragWithVelocity(-1.2f, releaseTimeNanos = time)
        motion.request(false)
        yield()
        assertEquals(.6f, motion.value, .0001f)
        assertEquals(-1.2f, motion.progress.velocity, .001f)
        frames(1)
        assertTrue("松手后的首个有效动画帧应继续移动", motion.value < .6f)
        frames(3)
        assertTrue(motion.value < .6f)
        val position = motion.value
        val velocity = motion.progress.velocity
        motion.request(true)
        assertEquals(position, motion.value, .0001f)
        frames(1)
        assertEquals(velocity, motion.progress.velocity, .001f)
        frames(120)
        assertEquals(MotionPhase.SHOWN, motion.phase)
        coroutineContext.cancelChildren()
    }
}
