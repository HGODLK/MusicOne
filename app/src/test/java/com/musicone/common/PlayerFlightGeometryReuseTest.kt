package com.musicone

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlayerFlightGeometryReuseTest {
    @Test fun cachedRotationsKeepTheOriginalFloatResults() {
        for (angle in listOf(-90f, -45.125f, -0f, 0f, .001f, 37.25f, 89.999f, 90f)) {
            val rotation = playerFlightRotation(angle)
            for (point in listOf(Offset.Zero, Offset(-171.375f, 920.25f), Offset(512.125f, -43.875f))) {
                assertEquals(phonePlayerFlightRotate(point, Offset.Zero, angle), rotation.rotate(point))
            }
        }
    }

    @Test fun threeElementsReuseOneFrameAndMatchTheUncachedPathAtEveryProgress() = runBlocking {
        for (rotation in listOf(90, -90)) {
            val motion = motion()
            val flight = PhonePlayerFlightRotation(this)
            val shared = playerSurfaceFlightState(motion, flight, rotation)
            for (side in listOf(0f, .25f, .5f, .75f, 1f)) {
                flight.complete()
                flight.beginDrag(rotation, .5f)
                flight.dragTo(side * .5f)
                for (step in 0..100) {
                    motion.dragTo(step / 100f)
                    val frame = requireNotNull(shared.value)
                    assertNotNull(frame.contentGeometry)
                    assertSame(frame.geometry, frame.contentGeometry!!.geometry)
                    for (key in listOf("cover", "title", "subtitle")) {
                        assertSame(frame, shared.value)
                        compare(motion, flight, rotation, key, frame)
                    }
                }
            }
        }
    }

    @Test fun barChangesHostResizeAndNewTextEndpointsStayLive() = runBlocking {
        val motion = motion()
        val flight = PhonePlayerFlightRotation(this)
        val shared = playerSurfaceFlightState(motion, flight, 90)
        motion.dragTo(.08f)
        val first = shared.value!!
        motion.sources["surface"] = MotionAnchor(Rect(30f, 860f, 470f, 950f))
        val moved = shared.value!!
        assertNotSame(first, moved)
        assertNotEquals(first.contentGeometry!!.sourceBounds, moved.contentGeometry!!.sourceBounds)
        compare(motion, flight, 90, "cover", moved)
        motion.updateHost(Rect(0f, 24f, 700f, 1424f))
        val resized = shared.value!!
        assertNotSame(moved, resized)
        compare(motion, flight, 90, "cover", resized)
        val source = playerSourceAnchor(motion, "title")!!.bounds
        val target = motion.targetSnapshot.getValue("title").bounds
        // 文字自身的新端点继续实时参与，不能被公共背景帧缓存住。
        for (width in listOf(32f, 96f, 168f)) {
            val changed = Rect(target.left, target.top, target.left + width, target.bottom)
            assertSame(resized, shared.value)
            assertEquals(phonePlayerFlightGeometry(motion, flight, 90, "title", source, changed),
                phonePlayerFlightGeometry(motion, flight, 90, "title", source, changed, resized))
        }
    }

    @Test fun heldTurnPosesAndPortraitFallbackKeepTheirExistingPaths() = runBlocking {
        val scope = CoroutineScope(coroutineContext + BroadcastFrameClock())
        try {
            for (rotation in listOf(0, 90, -90)) {
                val motion = motion()
                val flight = PhonePlayerFlightRotation(scope)
                val turn = PhonePlayerTurn(scope)
                turn.reset(rotation)
                val angle = if (rotation == 0) 37.5f else rotation * .625f
                turn.register("cover", Any(), PhoneTurnPose(Offset(260f, 350f), Size(220f, 220f), angle), rotation)
                turn.register("title", Any(), PhoneTurnPose(Offset(160f, 520f), Size(200f, 32f), angle), rotation)
                turn.register("artist", Any(), PhoneTurnPose(Offset(160f, 560f), Size(160f, 24f), angle), rotation)
                flight.beginTurnDrag(turn)
                val shared = playerSurfaceFlightState(motion, flight, rotation)
                for (progress in listOf(0f, .08f, .25f, .5f, .75f, 1f)) {
                    motion.dragTo(progress)
                    for (key in listOf("cover", "title", "subtitle")) {
                        compare(motion, flight, rotation, key, shared.value!!)
                    }
                }
                flight.complete()
            }
            val motion = motion()
            val portrait = playerSurfaceFlightState(motion, PhonePlayerFlightRotation(scope), 0).value!!
            assertNull(portrait.contentGeometry)
            val tablet = playerSurfaceFlightState(motion, null, 90).value!!
            assertNull(tablet.contentGeometry)
        } finally { coroutineContext.cancelChildren() }
    }

    private fun compare(motion: PageMotion, flight: PhonePlayerFlightRotation, rotation: Int,
        key: String, frame: PlayerSurfaceFlightFrame) {
        val source = playerSourceAnchor(motion, key)!!.bounds
        val target = motion.targetSnapshot.getValue(key).bounds
        assertEquals(phonePlayerFlightGeometry(motion, flight, rotation, key, source, target),
            phonePlayerFlightGeometry(motion, flight, rotation, key, source, target, frame))
    }

    private fun CoroutineScope.motion(): PageMotion = PageMotion(this, true, 320, 280).apply {
        updateHost(Rect(0f, 24f, 500f, 1024f))
        sources["surface"] = MotionAnchor(Rect(20f, 880f, 480f, 960f))
        sources["cover"] = MotionAnchor(Rect(32f, 892f, 88f, 948f))
        sources["title"] = MotionAnchor(Rect(100f, 895f, 280f, 917f))
        sources["subtitle"] = MotionAnchor(Rect(100f, 922f, 240f, 942f))
        targets["surface"] = MotionAnchor(hostBounds)
        targets["cover"] = MotionAnchor(Rect(180f, 124f, 380f, 324f))
        targets["title"] = MotionAnchor(Rect(90f, 350f, 120f, 590f))
        targets["subtitle"] = MotionAnchor(Rect(55f, 350f, 75f, 530f))
        beginDrag()
    }
}
