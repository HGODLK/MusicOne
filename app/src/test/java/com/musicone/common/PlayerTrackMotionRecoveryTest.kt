package com.musicone

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlayerTrackMotionRecoveryTest {
    @Test fun hiddenLyricsWaitForMotionButVisibleLyricsCanCrossfadeImmediately() {
        assertTrue(deferHiddenLyricTrackChange(moving = true, displayed = false))
        assertFalse(deferHiddenLyricTrackChange(moving = true, displayed = true))
        assertFalse(deferHiddenLyricTrackChange(moving = false, displayed = false))
        assertTrue(lyricTrackUsesFade(true, false, false, true))
    }

    @Test fun validEndpointsAreNotRereadEveryFrameAndReboundClockStillSettles() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            for (rotation in listOf(90, -90)) {
                val turn = PhonePlayerTurn(frames.scope)
                val token = Any()
                val replacement = Any()
                val start = PhoneTurnPose(Offset(100f, 200f), Size(300f, 180f), 0f)
                val target = PhoneTurnPose(Offset(450f, 650f), Size(500f, 200f), rotation.toFloat())
                var reads = 0
                turn.register("clock", token, start, 0)
                turn.begin(rotation)
                turn.attachEndpoint("clock", token, rotation) {
                    reads++
                    turn.register("clock", token, target, rotation)
                }
                turn.register("clock", token, target, rotation)
                val done = frames.scope.launch { turn.awaitSettled() }
                frames.advance(8)
                val before = turn.currentPose("clock")!!
                assertTrue(kotlin.math.abs(before.rotation) in .01f..89.99f)
                turn.attachEndpoint("clock", replacement, rotation) {
                    reads++
                    turn.register("clock", replacement, target, rotation)
                }
                turn.register("clock", replacement, target, rotation)
                turn.unregister("clock", token)
                assertEquals(before, turn.currentPose("clock"))
                frames.advance(180)
                assertTrue(done.isCompleted)
                assertFalse(turn.isTurning)
                assertEquals(target, turn.currentPose("clock"))
                assertEquals(0, reads)
            }
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun cancelledElementRecoversBeforeAnotherElementFinishesItsTurn() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val turn = PhonePlayerTurn(frames.scope)
            val source = PhoneTurnPose(Offset(100f, 200f), Size(300f, 300f), 0f)
            val target = PhoneTurnPose(Offset(450f, 650f), Size(240f, 240f), 90f)
            val cover = Any()
            val clock = Any()
            turn.register("cover", cover, source, 0)
            turn.register("clock", clock, source, 0)
            turn.begin(90)
            turn.register("cover", cover, target, 90)
            turn.register("clock", clock, target, 90)
            val done = frames.scope.launch { turn.awaitSettled() }
            frames.advance(8)
            // 模拟重绑取消其中一组动画，其他元素与收尾任务继续运行。
            val groupsField = PhonePlayerTurn::class.java.getDeclaredField("groups").apply { isAccessible = true }
            val groups = groupsField.get(turn) as Map<*, *>
            val group = requireNotNull(groups["clock"])
            val jobField = group.javaClass.getDeclaredField("job").apply { isAccessible = true }
            (jobField.get(group) as Job).cancelAndJoin()
            val stopped = turn.currentPose("clock")!!
            frames.advance(6)
            assertNotEquals(stopped, turn.currentPose("clock"))
            assertTrue(turn.isTurning)
            frames.advance(180)
            assertTrue(done.isCompleted)
            assertEquals(target, turn.currentPose("cover"))
            assertEquals(target, turn.currentPose("clock"))
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun recoveryIgnoresExpiredLayoutVelocityButKeepsSpringMomentum() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val turn = PhonePlayerTurn(frames.scope)
            val source = PhoneTurnPose(Offset(100f, 200f), Size(300f, 300f), 0f)
            val target = PhoneTurnPose(Offset(450f, 650f), Size(240f, 240f), 90f)
            val cover = Any()
            val clock = Any()
            turn.register("cover", cover, source, 0)
            turn.register("clock", clock, source, 0)
            turn.begin(90)
            turn.register("cover", cover, target, 90)
            turn.register("clock", clock, target, 90)
            frames.scope.launch { turn.awaitSettled() }
            frames.advance(8)
            // 两组弹簧相同，仅时钟曾经历端点布局变化，随后布局已经停稳。
            turn.register("clock", clock, target.copy(pivot = target.pivot + Offset(100f, 0f)), 90)
            turn.register("clock", clock, target, 90)
            val groupsField = PhonePlayerTurn::class.java.getDeclaredField("groups").apply { isAccessible = true }
            val groups = groupsField.get(turn) as Map<*, *>
            for (key in listOf("cover", "clock")) {
                val group = requireNotNull(groups[key])
                val job = group.javaClass.getDeclaredField("job").apply { isAccessible = true }.get(group) as Job
                job.cancelAndJoin()
            }
            frames.advance(6)
            val coverPose = turn.currentPose("cover")!!
            val clockPose = turn.currentPose("clock")!!
            assertEquals(coverPose.pivot.x, clockPose.pivot.x, .01f)
            assertEquals(coverPose.pivot.y, clockPose.pivot.y, .01f)
            assertEquals(coverPose.rotation, clockPose.rotation, .01f)
            assertTrue(turn.isTurning)
            frames.advance(180)
            assertFalse(turn.isTurning)
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun rotationCrossfadeWaitsForLatestDrawnLyricsAndPreservesVisibleBlend() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        val held = LyricHeldTrackTransition()
        fun window(id: String) = LyricWindow(LyricPresentation(
            source = MusicSource.QQ, id = id, durationMs = 10000L,
            lyrics = listOf(TimedLyric(0L, id))))
        try {
            val a = window("a")
            val b = window("b")
            held.request(a, b, frames.scope, gesturePreparation = false)
            frames.advance(10)
            assertNull(held.preparation)
            assertTrue(held.awaitingLayout)
            assertEquals(1f, held.weight(a), 0f)
            b.ready.complete(Unit)
            frames.advance(10)
            val visible = held.layers.map { LyricHeldBlend(it.window, it.weight) }
            assertTrue(held.weight(b) in .001f.. .999f)
            val c = window("c")
            held.request(b, c, frames.scope, gesturePreparation = false)
            frames.advance(10)
            visible.forEach { assertEquals(it.weight, held.weight(it.window), .0001f) }
            assertTrue(held.awaitingLayout)
            assertEquals(0f, held.weight(c), 0f)
            c.ready.complete(Unit)
            frames.advance(60)
            assertFalse(held.active)
            assertEquals(1f, held.weight(c), 0f)
            assertEquals(listOf(c), held.layers.map { it.window })
        } finally { held.cancel(); coroutineContext.cancelChildren() }
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
