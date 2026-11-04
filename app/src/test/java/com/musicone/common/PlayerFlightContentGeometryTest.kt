package com.musicone

import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlayerFlightContentGeometryTest {
    @Test fun artworkAndTextStayInsideBothRotatingSurfacesThroughoutOpeningAndReturn() = runBlocking {
        for (rotation in listOf(90, -90)) {
            val motion = PageMotion(this, true, 320, 280)
            motion.updateHost(Rect(0f, 0f, 500f, 1000f))
            motion.sources["surface"] = MotionAnchor(Rect(20f, 880f, 480f, 960f))
            motion.targets["surface"] = MotionAnchor(motion.hostBounds)
            val flight = PhonePlayerFlightRotation(this)
            val sourceCover = Rect(32f, 892f, 88f, 948f)
            val sourceTitle = Rect(100f, 895f, 280f, 917f)
            val sourceArtist = Rect(100f, 922f, 240f, 942f)
            for ((key, source, target) in listOf(
                Triple("cover", sourceCover, Rect(180f, 100f, 380f, 300f)),
                Triple("title", sourceTitle, Rect(80f, 300f, 110f, 540f)),
                Triple("subtitle", sourceArtist, Rect(45f, 300f, 65f, 480f)))) {
                motion.sources[key] = MotionAnchor(source)
                motion.targets[key] = MotionAnchor(target)
            }
            motion.beginDrag()
            // 同时覆盖侧条完全竖起、途中归位以及普通横屏开场。
            for (sideProgress in listOf<Float?>(null, 0f, .25f, .5f, .75f, 1f)) {
                flight.complete()
                if (sideProgress != null) {
                    flight.beginDrag(rotation, .5f)
                    flight.dragTo(sideProgress * .5f)
                }
                for (step in 0..100) {
                    motion.dragTo(step / 100f)
                    val bar = phonePlayerFlightGeometry(motion, flight, rotation, "surface",
                        motion.sources.getValue("surface").bounds, motion.targets.getValue("surface").bounds)
                    for (key in listOf("cover", "title", "subtitle")) {
                        val pose = phonePlayerFlightGeometry(motion, flight, rotation, key,
                            motion.sources.getValue(key).bounds, motion.targets.getValue(key).bounds)
                        for (point in listOf(pose.bounds.topLeft, pose.bounds.topRight,
                            pose.bounds.bottomLeft, pose.bounds.bottomRight)) {
                            val physical = phonePlayerFlightRotate(point, pose.bounds.center, pose.rotation)
                            val local = phonePlayerFlightRotate(physical, bar.bounds.center, -bar.rotation)
                            assertTrue("$rotation/$sideProgress/$step/$key $local in ${bar.bounds}",
                                local.x >= bar.bounds.left - .01f && local.x <= bar.bounds.right + .01f &&
                                    local.y >= bar.bounds.top - .01f && local.y <= bar.bounds.bottom + .01f)
                        }
                    }
                }
            }
        }
    }

    @Test fun endpointsKeepActualCoverSizeAndPhysicalTargetInBothDirections() = runBlocking {
        for (rotation in listOf(90, -90)) {
            val motion = PageMotion(this, true, 320, 280)
            motion.updateHost(Rect(0f, 24f, 500f, 1024f))
            motion.sources["surface"] = MotionAnchor(Rect(20f, 880f, 480f, 960f))
            motion.targets["surface"] = MotionAnchor(motion.hostBounds)
            val source = Rect(32f, 892f, 88f, 948f)
            val target = Rect(180f, 100f, 380f, 300f)
            val flight = PhonePlayerFlightRotation(this)
            motion.beginDrag()
            for (progress in listOf(0f, 1f)) {
                motion.dragTo(progress)
                val pose = phonePlayerFlightGeometry(motion, flight, rotation, "cover", source, target)
                val expected = if (progress == 0f) source else target
                assertEquals(expected.center.x, pose.bounds.center.x, .001f)
                assertEquals(expected.center.y, pose.bounds.center.y, .001f)
                assertEquals(expected.width, pose.bounds.width, .001f)
            }
        }
    }
}
