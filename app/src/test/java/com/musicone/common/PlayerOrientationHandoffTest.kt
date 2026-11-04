package com.musicone

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.LayoutCoordinates
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class PlayerOrientationHandoffTest {
    @Test fun rotatingDuringLyricsExitKeepsTheVisibleProgressInBothDirections() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            var time = 0L
            suspend fun frames(count: Int) {
                repeat(count) {
                    yield(); time += 16_666_667L; clock.sendFrame(time)
                    Snapshot.sendApplyNotifications(); yield()
                }
            }
            val lyrics = PhoneLyricsMotion(true)
            var job = launch(start = CoroutineStart.UNDISPATCHED) { lyrics.moveTo(false) }
            frames(5)
            job.cancelAndJoin()
            val portraitPosition = lyrics.lyricsProgress
            assertTrue(portraitPosition > 0f && portraitPosition < 1f)
            job = launch(start = CoroutineStart.UNDISPATCHED) { lyrics.moveLandscape(false, 1200f) }
            assertEquals(portraitPosition, lyrics.lyricsProgress, .0001f)
            frames(3)
            job.cancelAndJoin()
            val landscapePosition = lyrics.lyricsProgress
            assertTrue(landscapePosition > 0f && landscapePosition < portraitPosition)
            job = launch(start = CoroutineStart.UNDISPATCHED) { lyrics.moveTo(true) }
            assertEquals(landscapePosition, lyrics.lyricsProgress, .0001f)
            frames(160)
            assertTrue(job.isCompleted)
            assertEquals(1f, lyrics.lyricsProgress, 0f)
            assertEquals(1f, lyrics.headerProgress, 0f)
        }
    }

    @Test fun placingTheNewHostDoesNotReleaseAnActiveSeekHold() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            var time = 0L
            val motion = LyricPlaybackStepMotion(0) { time }
            motion.row(1)
            val driver = launch(start = CoroutineStart.UNDISPATCHED) { motion.run() }
            try {
                motion.holdPosition(true)
                motion.holdHostPosition(true)
                motion.holdHostPosition(false)
                val before = motion.row(1).position.value
                motion.row(1).position.retarget(180f, lyricPlaybackMotionSpec(180f))
                motion.emphasize(1, 1, animate = true, playback = true)
                repeat(6) { yield(); time += 16_666_667L; clock.sendFrame(time); yield() }
                assertEquals(before, motion.row(1).position.value, 0f)
                assertTrue(motion.row(1).focus.value > 0f)
            } finally { driver.cancelAndJoin() }
        }
    }

    @Test fun detachedHostCannotReuseItsScrollLeaseAfterReattachment() {
        var attached = true
        val coordinates = Proxy.newProxyInstance(LayoutCoordinates::class.java.classLoader,
            arrayOf(LayoutCoordinates::class.java)) { _, method, _ ->
            check(method.name == "isAttached")
            attached
        } as LayoutCoordinates
        val host = LyricListHost()
        host.place(coordinates)
        val old = host.scrollLease()
        assertTrue(old())
        attached = false
        assertFalse(old())
        host.invalidate(cancelPrefetch = false)
        attached = true
        host.place(coordinates)
        assertFalse(old())
        assertTrue(host.scrollLease()())
    }

    @Test fun scrollFrameRejectsOldHostBeforeCoroutineCancellationAndCanResume() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            var time = 0L
            var valid = true
            var moved = 0f
            val motion = LyricPlaybackStepMotion(0) { time }
            motion.consume(1, true, resumeInterrupted = true)
            val driver = launch(start = CoroutineStart.UNDISPATCHED) { motion.run() }
            try {
                val first = async(start = CoroutineStart.UNDISPATCHED) {
                    motion.move(1, listOf(0, 1, 2), 180f, {
                        check(valid) { "旧列表脱离后仍收到滚动" }
                        moved += it
                        it
                    }, { valid })
                }
                repeat(6) { yield(); time += 16_666_667L; clock.sendFrame(time); yield() }
                val before = moved
                assertTrue(before > 0f && before < 180f)
                valid = false
                yield(); time += 16_666_667L; clock.sendFrame(time); yield()
                assertFalse(first.await())
                assertEquals(before, moved, 0f)
                assertEquals(LyricPlaybackStepChange.ANIMATE, motion.consume(1, true, resumeInterrupted = true))
                valid = true
                val resumed = async(start = CoroutineStart.UNDISPATCHED) {
                    motion.move(1, listOf(0, 1, 2), 180f - moved, { moved += it; it }, { valid })
                }
                repeat(160) { yield(); time += 16_666_667L; clock.sendFrame(time); yield() }
                assertTrue(resumed.await())
                assertEquals(180f, moved, .01f)
            } finally { driver.cancelAndJoin() }
        }
    }

    @Test fun clockEndpointRefreshesWithoutAnotherLayoutNotification() = runBlocking {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(coroutineContext + clock)
        try {
            val turn = PhonePlayerTurn(scope)
            val token = Any()
            var actualRotation = 90f
            turn.begin(-90)
            turn.attachEndpoint("clock", token, -90) {
                turn.register("clock", token,
                    PhoneTurnPose(Offset(100f, 200f), Size(300f, 100f), actualRotation), -90,
                    physicalRotation = -90, hidden = true)
            }
            val waiting = scope.async(start = CoroutineStart.UNDISPATCHED) { turn.awaitEndpoint("clock", -90) }
            assertFalse(waiting.isCompleted)
            actualRotation = -90f
            clock.sendFrame(16_666_667L); yield()
            assertTrue(waiting.await())
            assertTrue(turn.endpointReady("clock", -90))
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun missingGlassEndpointCancelsOpeningInsteadOfLeavingAnInvisibleTouchLayer() = runBlocking {
        val clock = BroadcastFrameClock()
        val motion = PageMotion(CoroutineScope(coroutineContext + clock), false, 320, 280,
            sharedAnchorKeys = PlayerSharedAnchorKeys)
        try {
            motion.updateHost(Rect(0f, 0f, 700f, 1200f))
            val anchor = MotionAnchor(Rect(100f, 100f, 300f, 300f))
            PlayerSharedAnchorKeys.forEach { motion.sources[it] = anchor; motion.targets[it] = anchor }
            motion.targets.remove("surface")
            motion.prepareSharedAnchors = { true }
            var aborted = 0
            motion.onPreparationAborted = { aborted++ }
            motion.request(true)
            yield()
            delay(200)
            assertEquals(MotionPhase.HIDDEN, motion.phase)
            assertFalse(motion.mounted)
            assertFalse(motion.wantsOpen)
            assertEquals(1, aborted)
            assertTrue(motion.targetSnapshot.isEmpty())
            // 同一页面能够再次正常打开，边框表面与其余飞行内容一起进入快照。
            motion.targets["surface"] = anchor
            motion.request(true)
            repeat(8) {
                yield(); clock.sendFrame(it * 16_666_667L); Snapshot.sendApplyNotifications(); yield()
            }
            assertEquals(MotionPhase.MOVING, motion.phase)
            assertEquals(PlayerSharedAnchorKeys, motion.targetSnapshot.keys)
        } finally { coroutineContext.cancelChildren() }
    }
}
