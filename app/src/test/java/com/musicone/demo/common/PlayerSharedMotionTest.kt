package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PlayerSharedMotionTest {
    @Test fun landingConvergesToTheLiveBarInsteadOfTheCapturedSize() {
        val captured = MotionAnchor(Rect(12f, 800f, 988f, 880f), corner = 19f)
        val current = MotionAnchor(Rect(20f, 820f, 980f, 882f), corner = 19f)
        assertEquals(captured, playerSourceAnchor(captured, current, .16f))
        val halfway = playerSourceAnchor(captured, current, .08f)
        assertTrue(halfway.bounds.width < captured.bounds.width)
        assertTrue(halfway.bounds.width > current.bounds.width)
        assertEquals(current, playerSourceAnchor(captured, current, 0f))
    }

    @Test fun ordinaryStableSourcesKeepTheirOriginalGeometryThroughoutTheHandoff() {
        val anchor = MotionAnchor(Rect(12f, 800f, 988f, 862f), corner = 19f, contentScale = .9f)
        listOf(0f, .04f, .08f, .16f, .5f, 1f).forEach {
            assertEquals(anchor, playerSourceAnchor(anchor, anchor, it))
        }
    }

    @Test fun openingWaitsForTheLateControlAnchorEvenWhenTheCoverIsAlreadyStable() = runBlocking {
        val clock = BroadcastFrameClock()
        val motion = PageMotion(CoroutineScope(coroutineContext + clock), false, 480, 360,
            sharedAnchorKeys = setOf("cover", "play"))
        motion.updateHost(Rect(0f, 0f, 1000f, 2000f))
        motion.sources["cover"] = MotionAnchor(Rect(20f, 1800f, 68f, 1848f))
        motion.sources["play"] = MotionAnchor(Rect(900f, 1800f, 948f, 1848f))
        motion.targets["cover"] = MotionAnchor(Rect(100f, 100f, 500f, 500f))
        motion.request(true)
        var time = 0L
        suspend fun frames(count: Int) {
            repeat(count) {
                yield()
                time += 16_666_667L
                clock.sendFrame(time)
                yield()
            }
        }
        frames(4)
        assertEquals(MotionPhase.PREPARING, motion.phase)
        motion.targets["play"] = MotionAnchor(Rect(450f, 1600f, 520f, 1670f))
        frames(4)
        assertEquals(MotionPhase.MOVING, motion.phase)
        assertNotNull(motion.targetSnapshot["play"])
        coroutineContext.cancelChildren()
    }

    @Test fun hiddenLyricsResetTheirHeaderAndWindowBeforeAnotherOpening() = runBlocking {
        val motion = PhoneLyricsMotion(true)
        motion.beginDrag()
        motion.dragTo(.7f)
        motion.resetWhileHidden()
        assertEquals(0f, motion.lyricsProgress, 0f)
        assertEquals(0f, motion.headerProgress, 0f)
        assertFalse(motion.dragging)
        assertNull(motion.openingAlignment)
    }
}
