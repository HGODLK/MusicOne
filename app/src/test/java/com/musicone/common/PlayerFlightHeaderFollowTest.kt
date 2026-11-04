package com.musicone

import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerFlightHeaderFollowTest {
    @Test fun onlyHeaderChangesDuringTheFlightRefreshTheTarget() {
        val follow = PlayerFlightHeaderFollow()
        assertFalse(follow.changed(false, .4f))
        assertFalse(follow.changed(true, .4f))
        assertTrue(follow.changed(true, .6f))
        assertFalse(follow.changed(true, .6f))
        assertFalse(follow.changed(false, .8f))
        assertFalse(follow.changed(true, .8f))
    }

    @Test fun capturedTargetFollowsTheLiveAnchorWithoutRestartingTheDrag() = runBlocking<Unit> {
        val motion = PageMotion(this, true, 320, 280).apply { updateHost(Rect(0f, 0f, 500f, 1000f)) }
        val middle = MotionAnchor(Rect(100f, 200f, 300f, 400f))
        val end = MotionAnchor(Rect(20f, 40f, 80f, 100f))
        motion.targets["cover"] = middle
        motion.sources["cover"] = MotionAnchor(Rect(0f, 900f, 50f, 950f))
        assertTrue(motion.beginDrag())
        motion.dragTo(.6f)
        assertEquals(middle, motion.targetSnapshot["cover"])
        motion.targets["cover"] = end
        motion.targets["title"] = end
        motion.refreshCapturedTargets(PLAYER_HEADER_FLIGHT_KEYS)
        assertEquals(end, motion.targetSnapshot["cover"])
        // 未进入本批次的端点不能被中途加入。
        assertNull(motion.targetSnapshot["title"])
        assertEquals(.6f, motion.value, 0f)
    }
}
