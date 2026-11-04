package com.musicone

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PhoneLyricsRotationGateTest {
    @Test fun bothHeaderAndLyricsAndFallbackOpacityMustReachTheRequestedEndpoint() = runBlocking {
        val motion = PhoneLyricsMotion(false)
        assertTrue(motion.settledForRotation(false))
        motion.lyrics.snapTo(1f)
        assertFalse(motion.settledForRotation(true))
        motion.header.snapTo(1f)
        assertTrue(motion.settledForRotation(true))
        motion.entranceAlpha.snapTo(.7f)
        assertFalse(motion.settledForRotation(true))
        motion.entranceAlpha.snapTo(1f)
        motion.beginDrag()
        assertFalse(motion.settledForRotation(true))
        motion.finishDrag()
        assertTrue(motion.settledForRotation(true))
        assertFalse(motion.settledForRotation(false))
    }

    @Test fun openingPreparationAndGestureDoNotStartOrConsumeTurnPreparation() = withFrames {
        val motion = PhoneLyricsMotion(false)
        val gate = PhoneLyricsRotationGate()
        gate.attach(Any(), motion)
        val opening = scope.launch { motion.moveTo(true, prepareOpening = true) }
        advance(2)
        assertNotNull(motion.openingAlignment)
        var passed = false
        val wait = scope.launch { gate.await { true }; passed = true }
        advance(4)
        assertFalse(passed)
        motion.openingAlignment!!.complete(Unit)
        advance(2)
        assertFalse(passed)
        advance(180)
        assertTrue(opening.isCompleted)
        assertTrue(wait.isCompleted && passed)
    }

    @Test fun latestDirectionWinsAndReturningToTheCurrentDirectionCancelsThePendingTurn() = withFrames {
        val motion = PhoneLyricsMotion(false)
        motion.lyrics.snapTo(.5f); motion.header.snapTo(.5f)
        val presentation = PhonePlayerPresentation(scope)
        presentation.portraitLyrics.value = true
        presentation.lyricsRotationGate.attach(Any(), motion)
        var captures = 0
        presentation.preparation.attach(Any()) { captures++ }
        presentation.showRotation(90)
        advance(2)
        assertFalse(presentation.preparation.active)
        assertEquals(0, captures)
        presentation.showRotation(-90)
        advance(2)
        presentation.showRotation(0)
        motion.lyrics.snapTo(1f); motion.header.snapTo(1f)
        advance(4)
        assertEquals(0, captures)
        motion.lyrics.snapTo(.5f); motion.header.snapTo(.5f)
        presentation.showRotation(90)
        presentation.showRotation(-90)
        advance(2)
        assertEquals(0, captures)
        motion.lyrics.snapTo(1f); motion.header.snapTo(1f)
        advance(2)
        assertTrue(presentation.preparation.preparing)
        assertEquals(1, captures)
        assertEquals(-90, presentation.rotationDegrees)
        presentation.reset()
    }

    @Test fun closingReadsItsFinalVisibilityAndDismissalDropsTheQueuedDirection() = withFrames {
        val motion = PhoneLyricsMotion(true)
        motion.lyrics.snapTo(.4f); motion.header.snapTo(.2f)
        val presentation = PhonePlayerPresentation(scope)
        presentation.lyricsRotationGate.attach(Any(), motion)
        var captures = 0
        presentation.preparation.attach(Any()) { captures++; awaitCancellation() }
        presentation.showRotation(-90)
        advance(2)
        assertFalse(presentation.preparation.active)
        presentation.requestDismiss { }
        motion.lyrics.snapTo(0f); motion.header.snapTo(0f)
        advance(4)
        assertEquals(0, captures)
        presentation.reset()
    }

    @Test fun landscapeClosingReleasesOnlyAfterTheActualSharedMotionFinishes() = withFrames {
        val motion = PhoneLyricsMotion(true)
        val gate = PhoneLyricsRotationGate()
        gate.attach(Any(), motion)
        val closing = scope.launch { motion.moveLandscape(false, 1200f) }
        advance(3)
        val wait = scope.launch { gate.await { false } }
        advance(3)
        assertFalse(wait.isCompleted)
        advance(180)
        assertTrue(closing.isCompleted)
        assertTrue(wait.isCompleted)
        assertTrue(gate.ready(false))
    }

    @Test fun disposingAnOldOwnerDoesNotDetachTheCurrentMotion() = runBlocking {
        val gate = PhoneLyricsRotationGate()
        val old = Any()
        val current = Any()
        gate.attach(old, PhoneLyricsMotion(false))
        gate.attach(current, PhoneLyricsMotion(true))
        gate.detach(old)
        assertTrue(gate.ready(true))
        gate.detach(current)
        assertFalse(gate.ready(true))
        assertTrue(gate.ready(false))
    }

    private fun withFrames(block: suspend Frames.() -> Unit) = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            try { withTimeout(5_000) { Frames(this, clock).block() } }
            finally { coroutineContext.cancelChildren() }
        }
    }

    private class Frames(val scope: CoroutineScope, private val clock: BroadcastFrameClock) {
        private var time = 0L
        suspend fun advance(count: Int) { repeat(count) {
            yield(); time += 16_666_667L; clock.sendFrame(time)
            Snapshot.sendApplyNotifications(); yield()
        } }
    }
}
