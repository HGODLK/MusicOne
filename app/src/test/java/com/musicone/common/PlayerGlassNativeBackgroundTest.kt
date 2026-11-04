package com.musicone

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlayerGlassNativeBackgroundTest {
    @Test fun cacheRecordedDuringDrawingDoesNotChangeTheSelectedFrame() = runBlocking {
        val motion = motion()
        val flight = PhonePlayerFlightRotation(this)
        val surface = playerSurfaceFlightState(motion, flight, 90)
        var cacheReady = false
        val frame = playerGlassNativeFrameState(motion, flight, 90, false, surface, Density(1f)) { cacheReady }
        motion.dragTo(.25f)
        assertNull(frame.value)
        // 原画布在本帧才建立缓存时，背景节点和前景画布必须继续共用旧路径选择。
        cacheReady = true
        assertNull(frame.value)
        motion.dragTo(.26f)
        val next = requireNotNull(frame.value)
        assertSame(next, frame.value)
        assertEquals(.26f, next.progress, 0f)
    }

    @Test fun bothDirectionsKeepTheOriginalGeometryOpacityAndEndpointBranches() = runBlocking {
        val density = Density(2.75f)
        for (rotation in listOf(-90, 90)) {
            val motion = motion()
            val flight = PhonePlayerFlightRotation(this)
            val surface = playerSurfaceFlightState(motion, flight, rotation)
            val frame = playerGlassNativeFrameState(motion, flight, rotation, false, surface, density) { true }
            for (step in 0..100) {
                val progress = step / 100f
                motion.dragTo(progress)
                val current = frame.value
                if (progress == 0f || playerGlassAlpha(progress) == 0f) {
                    assertNull(current)
                } else {
                    val original = phonePlayerFlightGeometry(motion, flight, rotation, "surface",
                        playerSourceAnchor(motion, "surface")!!.bounds, motion.targetSnapshot.getValue("surface").bounds)
                    assertNotNull(current)
                    assertEquals(original.bounds.translate(-motion.hostBounds.left, -motion.hostBounds.top), current!!.bounds)
                    assertEquals(original.rotation, current.rotation, 0f)
                    assertEquals(with(density) { playerSurfaceCorner(progress).dp.toPx() }, current.corner, 0f)
                    assertEquals(playerGlassAlpha(progress), current.alpha, 0f)
                }
            }
        }
    }

    @Test fun availabilityAndWindowChangesInvalidateTheSharedFrame() = runBlocking {
        val motion = motion()
        val flight = PhonePlayerFlightRotation(this)
        val surface = playerSurfaceFlightState(motion, flight, 90)
        val available = mutableStateOf(true)
        val frame = playerGlassNativeFrameState(motion, flight, 90, false, surface, Density(1f)) { available.value }
        motion.dragTo(.5f)
        val first = requireNotNull(frame.value)
        available.value = false
        assertNull(frame.value)
        available.value = true
        assertNotNull(frame.value)
        motion.updateHost(Rect(20f, 40f, 740f, 1480f))
        val resized = requireNotNull(frame.value)
        assertNotEquals(first.bounds, resized.bounds)
        assertEquals(surface.value!!.geometry.bounds.translate(-motion.hostBounds.left, -motion.hostBounds.top), resized.bounds)
        assertEquals(.5f, resized.progress, 0f)
    }

    @Test fun portraitTabletAndPrewarmingKeepTheOriginalRenderer() = runBlocking {
        val motion = motion()
        val flight = PhonePlayerFlightRotation(this)
        val surface = playerSurfaceFlightState(motion, flight, 90)
        motion.dragTo(.5f)
        assertNull(playerGlassNativeFrameState(motion, flight, 0, false, surface, Density(1f)) { true }.value)
        assertNull(playerGlassNativeFrameState(motion, null, 90, false, surface, Density(1f)) { true }.value)
        assertNull(playerGlassNativeFrameState(motion, flight, 90, true, surface, Density(1f)) { true }.value)
    }

    private fun CoroutineScope.motion() = PageMotion(this, true, 320, 280,
        sharedAnchorKeys = setOf("surface")).apply {
        updateHost(Rect(10f, 30f, 490f, 990f))
        sources["surface"] = MotionAnchor(Rect(30f, 880f, 470f, 960f))
        targets["surface"] = MotionAnchor(hostBounds)
        beginDrag()
    }
}
