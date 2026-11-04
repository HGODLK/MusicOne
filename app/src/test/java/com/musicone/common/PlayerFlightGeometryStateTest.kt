package com.musicone

import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlayerFlightGeometryStateTest {
    @Test fun sharedSurfaceRetainsOneResultAndTracksBothLandscapeDirections() = runBlocking {
        for (rotation in listOf(0, 90, -90)) {
            val motion = motion()
            val flight = PhonePlayerFlightRotation(this)
            val state = playerSurfaceFlightState(motion, flight, rotation)
            assertNull(state.value)
            assertTrue(motion.beginDrag())
            for (progress in listOf(0f, .15f, .5f, 1f, .5f, 0f)) {
                motion.dragTo(progress)
                val frame = requireNotNull(state.value)
                // 多个绘制者读取同一个对象，进度改变后仍必须匹配原求解路径。
                assertSame(frame, state.value)
                val source = requireNotNull(playerSourceAnchor(motion, "surface"))
                val target = motion.targetSnapshot.getValue("surface")
                assertEquals(phonePlayerFlightGeometry(motion, flight, rotation, "surface", source.bounds, target.bounds), frame.geometry)
                assertEquals(phonePlayerFlightSource(motion, flight, rotation, "surface", source.bounds), frame.sourceGeometry)
            }
        }
    }

    @Test fun liveMiniBarAndHostResizeInvalidateSharedSurface() = runBlocking {
        val motion = motion()
        val flight = PhonePlayerFlightRotation(this)
        val state = playerSurfaceFlightState(motion, flight, 90)
        motion.beginDrag()
        motion.dragTo(0f)
        val before = state.value!!
        motion.sources["surface"] = MotionAnchor(Rect(30f, 850f, 470f, 950f))
        val moved = state.value!!
        assertNotEquals(before.geometry.bounds, moved.geometry.bounds)
        assertEquals(motion.sources.getValue("surface").bounds, moved.geometry.bounds)
        motion.dragTo(.5f)
        val oldTarget = state.value!!.targetBounds
        motion.updateHost(Rect(0f, 0f, 1000f, 2000f))
        assertNotEquals(oldTarget, state.value!!.targetBounds)
        assertEquals(motion.targetSnapshot.getValue("surface").bounds, state.value!!.targetBounds)
    }

    @Test fun controlPairReusesPoseAndRefreshesCapturedTarget() = runBlocking {
        val motion = motion()
        val flight = PhonePlayerFlightRotation(this)
        motion.coverKey = "play"
        val state = playerControlFlightState(motion, flight, -90, "play")
        assertNull(state.value)
        motion.beginDrag()
        motion.dragTo(.6f)
        val before = state.value!!
        assertSame(before, state.value)
        motion.replaceCapturedCoverTarget(MotionAnchor(Rect(300f, 400f, 450f, 550f)))
        val after = state.value!!
        assertNotEquals(before, after)
        assertEquals(phonePlayerFlightGeometry(motion, flight, -90, "play",
            playerSourceAnchor(motion, "play")!!.bounds, motion.targetSnapshot.getValue("play").bounds), after)
    }

    private fun kotlinx.coroutines.CoroutineScope.motion(): PageMotion =
        PageMotion(this, true, 320, 280, sharedAnchorKeys = setOf("surface", "play")).apply {
            updateHost(Rect(0f, 0f, 500f, 1000f))
            sources["surface"] = MotionAnchor(Rect(20f, 880f, 480f, 960f))
            targets["surface"] = MotionAnchor(Rect(0f, 0f, 500f, 1000f))
            sources["play"] = MotionAnchor(Rect(400f, 900f, 440f, 940f))
            targets["play"] = MotionAnchor(Rect(200f, 450f, 280f, 530f))
        }
}
