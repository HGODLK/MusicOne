package com.musicone

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PhoneClockHandoffTest {
    @Test fun cancelledOldOwnerCannotClearTheReplacementOrTriggerAnotherEntry() {
        val handoff = PhoneClockHandoff()
        val old = handoff.begin()
        val replacement = handoff.begin()
        assertFalse(handoff.finish(old))
        assertTrue(handoff.running)
        assertFalse(handoff.recoverIfIdle(0f, 0f, 0f, false, true, false))
        assertTrue(handoff.finish(replacement))
        assertFalse(handoff.running)
        assertTrue(handoff.recoverIfIdle(0f, 0f, 0f, false, true, false))
        assertEquals(1, handoff.revision)
    }

    @Test fun ordinaryTrackChangesAndCompletedNativeFallbackDoNotReplayTheClock() {
        val handoff = PhoneClockHandoff()
        repeat(120) { assertFalse(handoff.recoverIfIdle(1f, 0f, 1f, true, false, false)) }
        assertFalse(handoff.recoverIfIdle(1f, 0f, 1f, false, true, true))
        assertEquals(0, handoff.revision)
        assertFalse(phoneClockDisplayReady(1f, 0f, 0f, true, false, false))
        assertFalse(phoneClockDisplayReady(1f, 0f, 1f, true, true, false))
    }

    @Test fun interruptedEntranceResumesItsActualPositionAndVelocity() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        val handoff = PhoneClockHandoff()
        val flight = PhoneClockFlight()
        try {
            suspend fun enter() {
                val owner = handoff.begin()
                try { flight.animateTo(1f, 1_000f) } finally { handoff.finish(owner) }
            }
            val first = frames.scope.launch { enter() }
            frames.advance(8)
            first.cancelAndJoin()
            val position = flight.progress.value
            val velocity = flight.velocity
            assertTrue(position > 0f && position < 1f)
            assertTrue(velocity > 0f)
            assertTrue(handoff.recoverIfIdle(position, velocity, 1f, true, false, false))
            val resumed = frames.scope.launch(start = CoroutineStart.UNDISPATCHED) { enter() }
            assertEquals(position, flight.progress.value, 0f)
            assertEquals(velocity, flight.velocity, 0f)
            frames.advance(180)
            assertTrue(resumed.isCompleted)
            assertEquals(1f, flight.progress.value, 0f)
            assertFalse(handoff.running)
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun cancelledClockCompensationCannotUseItsValidCoordinatesAsAnEntryReceipt() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val turn = PhonePlayerTurn(frames.scope)
            val token = Any()
            val source = PhoneTurnPose(Offset(100f, 200f), Size(300f, 180f), 0f)
            val target = source.copy(pivot = Offset(400f, 650f), rotation = 90f)
            turn.register("clock", token, source, 0)
            turn.begin(90)
            turn.register("clock", token, target, 90)
            frames.advance(6)
            assertFalse(turn.clockHandoffStalled(90))
            assertTrue(awaitPhoneClockEndpoint(turn, 90, 0))
            val field = PhonePlayerTurn::class.java.getDeclaredField("groups").apply { isAccessible = true }
            val group = requireNotNull((field.get(turn) as Map<*, *>)["clock"])
            val job = group.javaClass.getDeclaredField("job").apply { isAccessible = true }.get(group) as Job
            job.cancelAndJoin()
            assertTrue(turn.endpointReady("clock", 90))
            assertTrue(turn.clockHandoffStalled(90))
            assertFalse(awaitPhoneClockEndpoint(turn, 90, 0))
            assertFalse(withContext(frames.scope.coroutineContext) { awaitPhoneClockEndpoint(turn, 90, 20) })
            turn.hold()
            assertFalse(turn.clockHandoffStalled(90))
        } finally { coroutineContext.cancelChildren() }
    }

    private class Frames(parent: CoroutineScope) {
        private val clock = BroadcastFrameClock()
        val scope = CoroutineScope(parent.coroutineContext + clock)
        private var time = 0L
        suspend fun advance(count: Int) {
            repeat(count) {
                yield(); time += 16_666_667L
                clock.sendFrame(time); Snapshot.sendApplyNotifications(); yield()
            }
        }
    }
}
