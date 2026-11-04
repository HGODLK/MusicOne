package com.musicone

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PhonePlayerClockEntryTest {
    @Test fun exitWaitsForTheVisibleHandoffAndPausesDuringAnotherSnapshot() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val covered = mutableStateOf(true)
            val flight = PhoneClockFlight()
            flight.snapTo(1f)
            val exit = frames.scope.launch { flight.exitWhenVisible({ covered.value }, { 1_000f }) }
            frames.advance(20)
            assertEquals(1f, flight.progress.value, 0f)
            assertEquals(0f, flight.velocity, 0f)
            assertFalse(exit.isCompleted)

            covered.value = false
            frames.advance(8)
            assertTrue(flight.progress.value > 0f && flight.progress.value < 1f)
            assertTrue(flight.velocity < 0f)
            covered.value = true
            frames.advance(2)
            val position = flight.progress.value
            val velocity = flight.velocity
            frames.advance(20)
            assertEquals(position, flight.progress.value, 0f)
            assertEquals(velocity, flight.velocity, 0f)

            covered.value = false
            frames.advance(180)
            assertTrue(exit.isCompleted)
            assertEquals(0f, flight.progress.value, 0f)
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun aNewEntryCancelsTheWaitingExitWithoutALateSlideOut() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val covered = mutableStateOf(false)
            val flight = PhoneClockFlight()
            flight.snapTo(1f)
            val exit = frames.scope.launch { flight.exitWhenVisible({ covered.value }, { 1_000f }) }
            frames.advance(8)
            covered.value = true
            frames.advance(2)
            val position = flight.progress.value
            val velocity = flight.velocity
            exit.cancelAndJoin()
            assertEquals(position, flight.progress.value, 0f)
            assertEquals(velocity, flight.velocity, 0f)
            val entry = frames.scope.launch { flight.animateTo(1f, 1_000f) }
            covered.value = false
            frames.advance(180)
            assertTrue(entry.isCompleted)
            assertEquals(1f, flight.progress.value, 0f)
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun portraitExitIsNotRestartedByPreparationFallbackOrNewTurnGenerations() {
        val exit = phoneClockTransitionRequest(0f, 0, false, false, false, 1)
        assertEquals(exit, phoneClockTransitionRequest(0f, 0, true, true, false, 2))
        assertEquals(exit, phoneClockTransitionRequest(0f, -90, false, false, true, 3))
        assertFalse(exit == phoneClockTransitionRequest(1f, 90, false, false, false, 3))
    }

    @Test fun finishedFlightDoesNotMakeAStaleEndpointReady() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val flight = PhoneClockFlight()
            flight.snapTo(1f)
            val turn = PhonePlayerTurn(frames.scope)
            val token = Any()
            val old = PhoneTurnPose(Offset(300f, 400f), Size(500f, 300f), 90f)
            turn.register("clock", token, old, 90)
            turn.reset(-90)
            assertEquals(1f, flight.progress.value, 0f)
            assertFalse(awaitPhoneClockEndpoint(turn, -90, 0))
            val actual = old.copy(rotation = -90f)
            assertEquals(actual, turn.drawingPose("clock", actual, Offset.Zero))
            turn.register("clock", token, actual, -90, physicalRotation = -90)
            assertTrue(awaitPhoneClockEndpoint(turn, -90, 0))
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun pagePhaseRestartCannotResetAnOutstandingClockWaitToThreeSeconds() {
        val flight = PhoneClockFlight()
        val origin = 1_000_000_000L
        assertEquals(1_000L, flight.endpointBudget(1, 1_000L, origin))
        assertEquals(500L, flight.endpointBudget(1, 3_000L, origin + 500_000_000L))
        assertEquals(0L, flight.endpointBudget(1, 3_000L, origin + 1_100_000_000L))
        flight.clearEndpointWait()
        assertEquals(3_000L, flight.endpointBudget(1, 3_000L, origin + 1_100_000_000L))
    }

    @Test fun sameDirectionGenerationChangeDoesNotSilentlyAbandonTheClockEntry() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val turn = PhonePlayerTurn(frames.scope)
            turn.reset(90)
            var ready: Boolean? = null
            val job = frames.scope.launch { ready = awaitPhoneClockEndpoint(turn, 90, 3_000) }
            frames.advance(2)
            turn.reset(90)
            frames.advance(2)
            assertNull(ready)
            turn.register("clock", Any(), PhoneTurnPose(Offset(300f, 400f), Size(500f, 300f), 90f),
                90, physicalRotation = 90, hidden = true)
            frames.advance(2)
            assertTrue(job.isCompleted)
            assertEquals(true, ready)
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun invalidClockEndpointFallsBackWithinItsRemainingBudget() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val turn = PhonePlayerTurn(frames.scope)
            turn.reset(-90)
            turn.register("clock", Any(), PhoneTurnPose(Offset(300f, 400f), Size(500f, 300f), 0f),
                -90, physicalRotation = -90, hidden = true)
            var ready: Boolean? = null
            val job = frames.scope.launch { ready = awaitPhoneClockEndpoint(turn, -90, 40) }
            frames.advance(1)
            delay(70)
            assertTrue(job.isCompleted)
            assertEquals(false, ready)
            assertFalse(awaitPhoneClockEndpoint(turn, -90, 0))
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun openingLyricsDuringClockFlightPreservesMomentumAndAcceleratesIntoTheReturn() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val flight = PhoneClockFlight()
            val entering = frames.scope.launch { flight.animateTo(1f, 1_000f) }
            frames.advance(6)
            val position = flight.progress.value
            val velocity = flight.velocity
            assertTrue(position in 0f..1f)
            assertTrue(velocity > 0f)
            entering.cancelAndJoin()
            assertEquals(position, flight.progress.value, 0f)
            assertEquals(velocity, flight.velocity, 0f)
            val exiting = frames.scope.launch { flight.animateTo(0f, 1_000f) }
            yield()
            assertEquals(position, flight.progress.value, 0f)
            var furthest = position
            repeat(4) { frames.advance(1); furthest = maxOf(furthest, flight.progress.value) }
            assertTrue(furthest > position)
            frames.advance(8)
            assertTrue(flight.velocity < 0f)
            frames.advance(180)
            assertTrue(exiting.isCompleted)
            assertEquals(0f, flight.progress.value, 0f)
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun pagePhaseChangesOnlySnapAFreshClockAndNeverAnAlreadyMovingOne() {
        assertTrue(phoneClockRevealWithPage(true, 0f, 0f))
        assertFalse(phoneClockRevealWithPage(true, .4f, 2f))
        assertFalse(phoneClockRevealWithPage(true, 0f, -1f))
        assertFalse(phoneClockRevealWithPage(false, 0f, 0f))
    }

    private class Frames(parent: CoroutineScope) {
        private val clock = BroadcastFrameClock()
        val scope = CoroutineScope(parent.coroutineContext + clock)
        private var time = 0L
        suspend fun advance(count: Int) {
            repeat(count) {
                yield()
                time += 16_666_667L
                clock.sendFrame(time)
                Snapshot.sendApplyNotifications()
                yield()
            }
        }
    }
}
