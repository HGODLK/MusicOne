package com.musicone

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PlayerOpeningLayerPreparationTest {
    private val size = IntSize(500, 1000)

    @Test fun openingNeedsAllThreeCurrentSizeReferences() {
        val layers = PlayerOpeningLayerPreparation()
        layers.attach(Any())
        layers.begin()
        assertFalse(layers.ready(size))
        layers.record(PlayerOpeningLayer.BODY, size)
        layers.record(PlayerOpeningLayer.FLYING, size)
        assertFalse(layers.ready(size))
        layers.record(PlayerOpeningLayer.GLASS, size)
        assertTrue(layers.ready(size))
    }

    @Test fun reopeningCannotBorrowPreviousOpeningAndResizingNeedsNewRecords() {
        val layers = PlayerOpeningLayerPreparation()
        layers.attach(Any())
        layers.begin()
        PlayerOpeningLayer.entries.forEach { layers.record(it, size) }
        assertTrue(layers.ready(size))
        val previous = layers.generation
        layers.begin()
        assertTrue(layers.generation > previous)
        assertFalse(layers.ready(size))
        PlayerOpeningLayer.entries.forEach { layers.record(it, size) }
        val resized = IntSize(600, 1200)
        assertFalse(layers.ready(resized))
        layers.record(PlayerOpeningLayer.BODY, resized)
        assertFalse(layers.ready(resized))
        PlayerOpeningLayer.entries.forEach { layers.record(it, resized) }
        assertTrue(layers.ready(resized))
        assertFalse(layers.ready(IntSize.Zero))
    }

    @Test fun replacementHostCannotBorrowOldReferencesOrBeDetachedByItsPredecessor() {
        val layers = PlayerOpeningLayerPreparation()
        val old = Any()
        val current = Any()
        layers.attach(old)
        PlayerOpeningLayer.entries.forEach { layers.record(it, size) }
        layers.attach(current)
        assertFalse(layers.ready(size))
        layers.detach(old)
        assertFalse(layers.ready(size))
        PlayerOpeningLayer.entries.forEach { layers.record(it, size) }
        assertTrue(layers.ready(size))
        layers.detach(current)
        assertTrue(layers.ready(size))
    }

    @Test fun coordinateReadinessAloneCannotStartLandscapeButPortraitIsUnchanged() = runBlocking {
        val motion = PageMotion(this, false, 320, 280)
        motion.updateHost(Rect(0f, 0f, 500f, 1000f))
        val anchors = PhonePlayerOpeningAnchors()
        anchors.layers.attach(Any())
        for (rotation in listOf(90, -90)) {
            anchors.begin(rotation)
            val batch = mapOf(
                "cover" to MotionAnchor(Rect(100f, 200f, 300f, 400f)),
                "title" to MotionAnchor(Rect(100f, 420f, 300f, 450f)),
                "subtitle" to MotionAnchor(Rect(100f, 460f, 300f, 480f)))
            assertTrue(anchors.publish(anchors.generation, rotation, batch, motion))
            assertFalse(anchors.ready(motion))
            PlayerOpeningLayer.entries.forEach { anchors.layers.record(it, size) }
            assertTrue(anchors.ready(motion))
        }
        anchors.begin(0)
        assertTrue(anchors.ready(motion))
    }

    @Test fun animationWaitsForThePreparedReferencesWithinItsExistingPreparationPhase() = runBlocking {
        val clock = BroadcastFrameClock()
        val motion = PageMotion(CoroutineScope(coroutineContext + clock), false, 320, 280,
            sharedAnchorKeys = PhonePlayerOpeningAnchors.Keys)
        motion.updateHost(Rect(0f, 0f, 500f, 1000f))
        val anchors = PhonePlayerOpeningAnchors()
        anchors.layers.attach(Any())
        anchors.begin(90)
        val batch = mapOf(
            "cover" to MotionAnchor(Rect(100f, 200f, 300f, 400f)),
            "title" to MotionAnchor(Rect(100f, 420f, 300f, 450f)),
            "subtitle" to MotionAnchor(Rect(100f, 460f, 300f, 480f)))
        motion.sources.putAll(batch)
        anchors.publish(anchors.generation, 90, batch, motion)
        motion.prepareSharedAnchors = anchors::ready
        var nanos = 0L
        suspend fun frames(count: Int) {
            repeat(count) {
                yield()
                nanos += 16_666_667L
                clock.sendFrame(nanos)
                Snapshot.sendApplyNotifications()
                yield()
            }
        }
        try {
            motion.request(true)
            frames(5)
            assertEquals(MotionPhase.PREPARING, motion.phase)
            anchors.layers.record(PlayerOpeningLayer.BODY, size)
            anchors.layers.record(PlayerOpeningLayer.GLASS, size)
            frames(3)
            assertEquals(MotionPhase.PREPARING, motion.phase)
            anchors.layers.record(PlayerOpeningLayer.FLYING, size)
            frames(5)
            assertEquals(MotionPhase.MOVING, motion.phase)
        } finally { coroutineContext.cancelChildren() }
    }
}
