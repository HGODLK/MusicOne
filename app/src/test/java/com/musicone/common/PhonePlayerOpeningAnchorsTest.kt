package com.musicone

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PhonePlayerOpeningAnchorsTest {
    @Test fun openingCannotCaptureOldPortraitTargetsBeforeTheNewLandscapeBatchIsPublished() = runBlocking {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(coroutineContext + clock)
        var frame = 0L
        suspend fun frames(count: Int) {
            repeat(count) {
                yield()
                frame += 16_666_667L
                clock.sendFrame(frame)
                Snapshot.sendApplyNotifications()
                yield()
            }
        }
        try {
            val motion = PageMotion(scope, false, 320, 280, sharedAnchorKeys = PhonePlayerOpeningAnchors.Keys)
            motion.updateHost(Rect(0f, 0f, 700f, 1200f))
            val anchors = PhonePlayerOpeningAnchors()
            anchors.begin(90)
            val oldPortrait = batch()
            motion.sources.putAll(oldPortrait)
            motion.targets.putAll(oldPortrait)
            motion.prepareSharedAnchors = anchors::ready
            motion.request(true)
            frames(4)
            assertEquals(MotionPhase.PREPARING, motion.phase)
            assertTrue(motion.targetSnapshot.isEmpty())
            val landscape = oldPortrait + ("cover" to MotionAnchor(Rect(300f, 400f, 600f, 700f)))
            assertTrue(anchors.publish(anchors.generation, 90, landscape, motion))
            frames(6)
            assertEquals(MotionPhase.MOVING, motion.phase)
            assertEquals(landscape, motion.targetSnapshot)
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun staleOrIncompleteLandscapeBatchesCannotBecomeThisOpeningTargets() = runBlocking {
        val motion = PageMotion(CoroutineScope(coroutineContext), true, 320, 280)
        motion.updateHost(Rect(0f, 0f, 700f, 1200f))
        val anchors = PhonePlayerOpeningAnchors()
        anchors.begin(90)
        val old = anchors.generation
        val batch = batch()
        assertFalse(anchors.publish(old, 0, batch, motion))
        assertFalse(anchors.publish(old, 90, batch - "subtitle", motion))
        assertFalse(anchors.ready(motion))
        assertTrue(anchors.publish(old, 90, batch, motion))
        assertTrue(anchors.ready(motion))
        val captured = motion.targets.toMap()
        anchors.begin(-90)
        assertFalse(anchors.publish(old, 90, batch, motion))
        assertFalse(anchors.ready(motion))
        assertEquals(captured, motion.targets.toMap())
        assertTrue(anchors.publish(anchors.generation, -90, batch, motion))
        assertTrue(anchors.ready(motion))
        coroutineContext.cancelChildren()
    }

    @Test fun anExplicitlyMeasuredEmptyArtistDoesNotBlockOpening() = runBlocking {
        val motion = PageMotion(CoroutineScope(coroutineContext), true, 320, 280)
        motion.updateHost(Rect(0f, 0f, 700f, 1200f))
        val anchors = PhonePlayerOpeningAnchors()
        anchors.begin(90)
        val emptyArtist = batch() + ("subtitle" to MotionAnchor(Rect(40f, 350f, 40f, 380f)))
        assertTrue(anchors.publish(anchors.generation, 90, emptyArtist, motion))
        assertTrue(anchors.ready(motion))
        coroutineContext.cancelChildren()
    }

    @Test fun newDirectionCannotReuseTheOldPhysicalCanvasAxis() {
        assertTrue(phoneLandscapeCanvasMatchesRotation(Offset(0f, 1f), Offset(0f, 1f)))
        assertFalse(phoneLandscapeCanvasMatchesRotation(Offset(0f, 1f), Offset(0f, -1f)))
        assertFalse(phoneLandscapeCanvasMatchesRotation(Offset(1f, 0f), Offset(0f, 1f)))
        assertFalse(phoneLandscapeCanvasMatchesRotation(Offset.Zero, Offset(0f, 1f)))
    }

    @Test fun batchUsesOneCanvasTransformAndIncludesTheVisiblePausedCoverScale() {
        val local = MotionAnchor(Rect(20f, 30f, 120f, 130f), corner = 9.36f, contentScale = .78f)
        val physical = phoneLandscapePhysicalAnchor(local) { Offset(700f - it.y, it.x + 100f) }
        assertEquals(Rect(570f, 120f, 670f, 220f), physical.bounds)
        assertEquals(local.corner, physical.corner, 0f)
        assertEquals(local.contentScale, physical.contentScale, 0f)
    }

    @Test fun cuttingTracksKeepsTheOpeningGenerationAndDoesNotReplaceAnActiveFlightSnapshot() = runBlocking {
        val motion = PageMotion(CoroutineScope(coroutineContext), true, 320, 280)
        motion.updateHost(Rect(0f, 0f, 700f, 1200f))
        val anchors = PhonePlayerOpeningAnchors()
        anchors.begin(90)
        val generation = anchors.generation
        val batch = batch()
        motion.sources.putAll(batch)
        assertTrue(anchors.publish(generation, 90, batch, motion))
        assertTrue(motion.beginDrag())
        val captured = motion.targetSnapshot
        val next = batch + ("title" to MotionAnchor(Rect(40f, 300f, 300f, 340f)))
        assertTrue(anchors.publish(generation, 90, next, motion))
        assertEquals(generation, anchors.generation)
        assertEquals(captured, motion.targetSnapshot)
        assertEquals(next["title"], motion.targets["title"])
        coroutineContext.cancelChildren()
    }

    @Test fun nearPortraitSideCompensationIsContinuousAndTheBottomEndpointIsExact() = runBlocking {
        for (angle in listOf(-90f, -45f, -.02f, 0f, .02f, 45f, 90f)) {
            val side = phonePlayerFlightHeldSide(0, angle)
            assertEquals(angle, side.first * side.second, .00001f)
        }
        assertEquals(90 to 1f, phonePlayerFlightHeldSide(90, 90f))
        val motion = PageMotion(CoroutineScope(coroutineContext), true, 320, 280)
        motion.updateHost(Rect(0f, 0f, 700f, 1200f))
        val source = Rect(30f, 1100f, 90f, 1160f)
        val target = Rect(200f, 200f, 440f, 440f)
        motion.sources["cover"] = MotionAnchor(source)
        motion.targets["cover"] = MotionAnchor(target)
        motion.beginDrag()
        motion.dragTo(0f)
        val flight = PhonePlayerFlightRotation(CoroutineScope(coroutineContext))
        flight.complete()
        val geometry = phonePlayerFlightGeometry(motion, flight, 0, "cover", source, target)
        assertEquals(source, geometry.bounds)
        assertEquals(0f, geometry.rotation, 0f)
        coroutineContext.cancelChildren()
    }

    private fun batch() = mapOf(
        "cover" to MotionAnchor(Rect(100f, 100f, 300f, 300f)),
        "title" to MotionAnchor(Rect(40f, 300f, 250f, 340f)),
        "subtitle" to MotionAnchor(Rect(40f, 350f, 200f, 380f)))
}
