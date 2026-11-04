package com.musicone

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PhonePlayerTurnPreparationTest {
    @Test fun failedSourceCaptureKeepsTheOriginalDirectionInsteadOfPlacingAnUnpreparedTarget() = runBlocking {
        val preparation = PhonePlayerTurnPreparation()
        val turn = PhonePlayerTurn(CoroutineScope(coroutineContext))
        preparation.attach(Any()) { error("模拟源画面捕获失败") }
        var placed = false
        val ready = preparation.prepare(turn, 90, emptySet()) { placed = true }
        assertFalse(ready)
        assertFalse(placed)
        assertEquals(0, turn.targetRotation)
        preparation.reset()
        assertFalse(preparation.active)
    }

    @Test fun compensatedStartRequiresItsOwnDrawAndStableInnerGeometry() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val preparation = PhonePlayerTurnPreparation()
            val turn = PhonePlayerTurn(frames.scope)
            preparation.attach(Any()) { }
            var textReady = true
            var staged = false
            var result: Boolean? = null
            val job = frames.scope.launch {
                result = preparation.prepare(turn, 90, emptySet(), contentReady = { textReady },
                    prepareMotion = { staged = true; textReady = false }) { }
            }
            frames.advance(1)
            preparation.targetDrawn()
            frames.advance(4)
            assertTrue(staged)
            assertNull(result)
            assertTrue(preparation.preparing)
            textReady = true
            // 第一阶段的绘制回执不能冒充补偿起点的绘制。
            frames.advance(4)
            assertNull(result)
            frames.drawTarget(preparation)
            assertTrue(job.isCompleted)
            assertEquals(true, result)
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun readingOriginIsTakenOnTheAcceptedCaptureFrameInsteadOfTheRotationRequest() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val preparation = PhonePlayerTurnPreparation()
            val turn = PhonePlayerTurn(frames.scope)
            var readingOffset = 10
            var sourceReady = false
            var captured: Int? = null
            var targetPlaced = false
            preparation.attach(Any()) {
                while (!preparation.sourceFrameDrawn()) androidx.compose.runtime.withFrameNanos { }
            }
            val job = frames.scope.launch {
                preparation.prepare(turn, 90, emptySet(), captureReading = {
                    if (sourceReady) captured = readingOffset
                    sourceReady
                }) { targetPlaced = true }
            }
            frames.advance(3)
            assertNull(captured)
            assertFalse(targetPlaced)
            readingOffset = 77
            sourceReady = true
            frames.advance(2)
            assertEquals(77, captured ?: -1)
            assertTrue(targetPlaced)
            frames.drawTarget(preparation)
            assertTrue(job.isCompleted)
            turn.releasePreparation()
            preparation.release()
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun targetLayoutWaitsForBothPageAndWholeLyricsCaptures() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val preparation = PhonePlayerTurnPreparation()
            val page = CompletableDeferred<Unit>()
            val lyrics = CompletableDeferred<Unit>()
            preparation.attach(Any()) { page.await() }
            val turn = PhonePlayerTurn(frames.scope)
            var placed = false
            val job = frames.scope.launch {
                preparation.prepare(turn, 90, emptySet(), captureContent = { lyrics.await() }) { placed = true }
            }
            frames.advance(1)
            page.complete(Unit)
            frames.advance(2)
            assertFalse(placed)
            lyrics.complete(Unit)
            frames.advance(1)
            assertTrue(placed)
            frames.drawTarget(preparation)
            assertTrue(job.isCompleted)
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun changingTargetPositionOrInnerLyricGeometryRestartsTheThreeFramePreparation() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val preparation = PhonePlayerTurnPreparation()
            preparation.attach(Any()) { }
            val turn = PhonePlayerTurn(frames.scope)
            val token = Any()
            val target = PhoneTurnPose(Offset(200f, 300f), Size(300f, 500f), -90f)
            var lyricOffset = 0
            var result: Boolean? = null
            val job = frames.scope.launch {
                result = preparation.prepare(turn, -90, setOf("lyrics"),
                    contentGeometry = { lyricOffset }) { }
            }
            frames.advance(1)
            preparation.targetDrawn()
            repeat(6) { index ->
                turn.register("lyrics", token, target.copy(pivot = Offset(200f, 300f + index)), -90)
                frames.advance(1)
                assertNull(result)
            }
            turn.register("lyrics", token, target, -90)
            repeat(6) { lyricOffset++; frames.advance(1); assertNull(result) }
            frames.drawTarget(preparation)
            assertTrue(job.isCompleted)
            assertEquals(true, result)
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun outerDrawAndEndpointsCannotReleaseBeforeInnerLyricTextIsReady() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val preparation = PhonePlayerTurnPreparation()
            preparation.attach(Any()) { }
            val turn = PhonePlayerTurn(frames.scope)
            var textReady = false
            var ready: Boolean? = null
            val job = frames.scope.launch {
                ready = preparation.prepare(turn, 90, setOf("lyrics"), contentReady = { textReady }) { }
            }
            frames.advance(1)
            turn.register("lyrics", Any(), PhoneTurnPose(Offset(200f, 300f), Size(300f, 500f), 90f), 90)
            preparation.targetDrawn()
            frames.advance(8)
            assertNull(ready)
            assertTrue(turn.preparing)
            textReady = true
            frames.advance(1)
            textReady = false
            frames.advance(2)
            assertNull(ready)
            textReady = true
            frames.drawTarget(preparation)
            assertTrue(job.isCompleted)
            assertEquals(true, ready)
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun clockEntryBudgetStartsAfterThePreparedFrameIsReleased() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val preparation = PhonePlayerTurnPreparation()
            preparation.attach(Any()) { }
            val turn = PhonePlayerTurn(frames.scope)
            val job = frames.scope.launch { preparation.prepare(turn, -90, setOf("clock")) { } }
            frames.advance(1)
            assertEquals(0L, preparation.clockWaitMillis(turn.generation, Long.MAX_VALUE))
            turn.register("clock", Any(), PhoneTurnPose(Offset(200f, 300f), Size(300f, 500f), -90f), -90)
            frames.drawTarget(preparation)
            assertTrue(job.isCompleted)
            turn.releasePreparation()
            preparation.release()
            assertTrue(preparation.clockWaitMillis(turn.generation) in 1L..3_000L)
            preparation.clockEntered(turn.generation)
            assertEquals(3_000L, preparation.clockWaitMillis(turn.generation, Long.MAX_VALUE))
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun fallbackDisablesOnlyTurnCompensationAndKeepsIndependentLyricTravel() = runBlocking {
        val turn = PhonePlayerTurn(CoroutineScope(coroutineContext))
        val token = Any()
        val source = PhoneTurnPose(Offset(100f, 200f), Size(120f, 150f), 0f)
        val target = source.copy(rotation = 90f)
        turn.register("lyrics", token, source, 0)
        turn.begin(90, prepare = true)
        turn.register("lyrics", token, target, 90)
        assertEquals(target.pivot,
            turn.drawingPose("lyrics", target, Offset(-300f, 0f))?.pivot)
        turn.finishPreparationWithoutMotion()
        // 后续歌词开关仍应读取自己的位移，不能因为降级而变成瞬间显隐。
        for (offset in listOf(Offset(-300f, 0f), Offset(-120f, 0f), Offset.Zero)) {
            assertEquals(target.pivot + offset, turn.drawingPose("lyrics", target, offset)?.pivot)
        }
    }

    @Test fun allEndpointsAndSubmittedTargetAreRequiredBeforeOriginalSpringsStart() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            for (rotation in listOf(90, -90, 0)) {
                val preparation = PhonePlayerTurnPreparation()
                preparation.attach(Any()) { }
                val turn = PhonePlayerTurn(frames.scope)
                val token = Any()
                val source = PhoneTurnPose(Offset(100f, 200f), Size(120f, 150f), 45f)
                val target = source.copy(pivot = Offset(400f, 600f), rotation = rotation.toFloat())
                turn.register("cover", token, source, 45)
                var result: Boolean? = null
                val job = frames.scope.launch {
                    result = preparation.prepare(turn, rotation, setOf("cover", "title")) { }
                }
                frames.advance(1)
                turn.register("cover", token, target, rotation)
                preparation.targetDrawn()
                frames.advance(8)
                assertNull(result)
                assertEquals(source, turn.currentPose("cover"))
                turn.register("title", Any(), target, rotation)
                frames.drawTarget(preparation)
                assertTrue(job.isCompleted)
                assertEquals(true, result)
                assertEquals(source, turn.currentPose("cover"))
                turn.releasePreparation()
                preparation.release()
                val settling = frames.scope.launch { turn.awaitSettled() }
                frames.advance(180)
                assertTrue(settling.isCompleted)
                assertEquals(target, turn.currentPose("cover"))
                assertFalse(preparation.active)
            }
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun slowTargetKeepsWaitingAndNeverStartsAnUnpreparedTurn() = runBlocking {
        assertEquals(3_000L, PHONE_PLAYER_TURN_PREPARATION_MILLIS)
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val preparation = PhonePlayerTurnPreparation()
            preparation.attach(Any()) { }
            val turn = PhonePlayerTurn(frames.scope)
            val token = Any()
            val source = PhoneTurnPose(Offset(100f, 200f), Size(120f, 150f), 0f)
            turn.register("cover", token, source, 0)
            var result: Boolean? = null
            val job = frames.scope.launch {
                result = preparation.prepare(turn, 90, setOf("cover")) { }
            }
            frames.advance(3)
            preparation.targetDrawn()
            assertNull(result)
            // 故意不给目标端点，超过旧三秒截止也必须保留起点并继续准备。
            delay(3_050)
            assertNull(result)
            assertTrue(preparation.preparing)
            assertFalse(preparation.fading)
            assertFalse(turn.nativeFallback)
            assertEquals(source, turn.currentPose("cover"))
            val target = source.copy(pivot = Offset(400f, 600f), rotation = 90f)
            turn.register("cover", token, target, 90)
            frames.drawTarget(preparation)
            assertTrue(job.isCompleted)
            assertEquals(true, result)
            turn.releasePreparation()
            preparation.release()
            frames.advance(180)
            assertFalse(preparation.active)
            assertFalse(preparation.fading)
            assertEquals(target, turn.currentPose("cover"))
            assertFalse(turn.hasVisibleMotion(setOf("cover")))
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun cancelledCaptureCannotChangeDirectionAfterExitOrANewerRequest() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val preparation = PhonePlayerTurnPreparation()
            val captured = CompletableDeferred<Unit>()
            preparation.attach(Any()) { captured.await() }
            val turn = PhonePlayerTurn(frames.scope)
            var oldLayoutPlaced = false
            val old = frames.scope.launch {
                preparation.prepare(turn, 90, setOf("cover")) { oldLayoutPlaced = true }
            }
            frames.advance(1)
            old.cancelAndJoin()
            preparation.reset()
            turn.reset()
            captured.complete(Unit)
            frames.advance(3)
            assertFalse(oldLayoutPlaced)
            assertFalse(preparation.active)
            assertEquals(0, turn.targetRotation)
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun targetDrawIsRequiredEvenWhenCoordinatesHaveAlreadyRegistered() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val preparation = PhonePlayerTurnPreparation()
            preparation.attach(Any()) { }
            val turn = PhonePlayerTurn(frames.scope)
            var ready: Boolean? = null
            val job = frames.scope.launch {
                ready = preparation.prepare(turn, -90, setOf("cover")) { }
            }
            frames.advance(1)
            turn.register("cover", Any(), PhoneTurnPose(Offset(200f, 300f), Size(120f, 150f), -90f), -90)
            frames.advance(6)
            assertNull(ready)
            frames.drawTarget(preparation)
            assertTrue(job.isCompleted)
            assertEquals(true, ready)
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun redirectingAnInterruptedFadeStillWaitsForTheCompensatedStart() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val preparation = PhonePlayerTurnPreparation()
            val fading = frames.scope.launch { preparation.crossfade { } }
            frames.advance(5)
            val visibleAlpha = preparation.sourceAlpha.value
            assertTrue(visibleAlpha > 0f && visibleAlpha < 1f)
            fading.cancelAndJoin()
            var capturedAlpha = -1f
            preparation.attach(Any()) { capturedAlpha = preparation.sourceAlpha.value }
            val turn = PhonePlayerTurn(frames.scope)
            var geometric: Boolean? = null
            val redirect = frames.scope.launch {
                geometric = preparation.prepare(turn, -90, emptySet()) { }
            }
            frames.advance(1)
            frames.drawTarget(preparation)
            assertTrue(redirect.isCompleted)
            assertEquals(visibleAlpha, capturedAlpha, 0f)
            assertEquals(true, geometric)
            assertEquals(-90, turn.targetRotation)
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun rotatedRevealKeepsRoundedOutlineAndInverseRotationPreservesPhysicalContent() = runBlocking {
        val scope = CoroutineScope(coroutineContext)
        val motion = PageMotion(scope, true, 320, 280)
        val host = Rect(0f, 0f, 700f, 1200f)
        motion.updateHost(host)
        motion.sources["surface"] = MotionAnchor(Rect(20f, 1000f, 680f, 1100f))
        motion.targets["surface"] = MotionAnchor(host)
        assertTrue(motion.beginDrag())
        val flight = PhonePlayerFlightRotation(scope)
        for (rotation in listOf(-90, 0, 90)) for (progress in listOf(0f, .3f, .6f, 1f)) {
            motion.dragTo(progress)
            val geometry = playerRevealGeometry(motion, flight, rotation)
            val pivot = geometry.bounds.center
            val point = Offset(110f, 950f)
            val restored = phonePlayerFlightRotate(
                phonePlayerFlightRotate(point, pivot, -geometry.rotation), pivot, geometry.rotation)
            assertEquals(point.x, restored.x, .001f)
            assertEquals(point.y, restored.y, .001f)
            val shape = PlayerRevealShape(motion, flight, rotation)
            val outline = shape.createOutline(Size(700f, 1200f), LayoutDirection.Ltr, Density(1f))
            assertTrue(outline is Outline.Rounded)
            val roundRect = (outline as Outline.Rounded).roundRect
            assertEquals(geometry.bounds, Rect(roundRect.left, roundRect.top, roundRect.right, roundRect.bottom))
        }
    }

    private class Frames(parent: CoroutineScope) {
        private val clock = BroadcastFrameClock()
        val scope = CoroutineScope(parent.coroutineContext + clock)
        private var time = 0L
        suspend fun drawTarget(preparation: PhonePlayerTurnPreparation, count: Int = 10) {
            val gaps = listOf(8L, 100L, 12L, 240L, 11L, 16L)
            repeat(count) { preparation.targetDrawn(); advance(1, gaps[it % gaps.size] * 1_000_000L) }
        }
        suspend fun advance(count: Int, gapNanos: Long = 16_666_667L) {
            repeat(count) {
                yield()
                time += gapNanos
                clock.sendFrame(time)
                Snapshot.sendApplyNotifications()
                yield()
            }
        }
    }
}
