package com.musicone

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PhonePlayerTurnTest {
    @Test fun unchangedLayoutReportsDoNotWakeEndpointObserversButRebindingDoes() = runBlocking {
        val turn = PhonePlayerTurn(this)
        val token = Any()
        val pose = PhoneTurnPose(Offset(200f, 400f), Size(600f, 300f), 0f)
        turn.register("lyrics", token, pose, 0)
        var checks = 0
        val observer = launch {
            snapshotFlow { checks++; turn.endpointReady("lyrics", 0) }.collect { }
        }
        yield()
        val initialChecks = checks
        repeat(10) {
            turn.register("lyrics", token, pose, 0)
            Snapshot.sendApplyNotifications()
            yield()
        }
        assertEquals(initialChecks, checks)
        turn.register("lyrics", Any(), pose, 0)
        Snapshot.sendApplyNotifications()
        yield()
        assertTrue(checks > initialChecks)
        observer.cancelAndJoin()
    }

    @Test fun halfOpenQueueDoesNotFreezeTheReadingPivotOfSettledLandscapeLyrics() = runBlocking {
        for (rotation in listOf(90, -90)) {
            val turn = PhonePlayerTurn(this)
            val token = Any()
            val before = PhoneTurnPose(Offset(200f, 400f), Size(600f, 300f), rotation.toFloat())
            turn.register("lyrics", token, before, rotation)
            val locks = PhonePlayerMenuLocks(turn)
            val queue = Any()
            val nestedMenu = Any()
            locks.set(queue, true)
            locks.set(nestedMenu, true)
            assertTrue(locks.active)
            assertFalse(turn.isTurning)
            // 下一句改变实际阅读枢轴，不能被菜单冻结的旧姿态抵消。
            val nextLine = before.copy(pivot = before.pivot + Offset(0f, 42f))
            turn.register("lyrics", token, nextLine, rotation)
            assertEquals(nextLine, turn.currentPose("lyrics"))
            locks.set(queue, false)
            assertTrue(locks.active)
            locks.set(nestedMenu, false)
            assertFalse(locks.active)
            assertFalse(turn.isTurning)
        }
    }

    @Test fun queueDuringRealTurnStillPausesAndResumesTheExistingTrajectory() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val turn = PhonePlayerTurn(frames.scope)
            val token = Any()
            val source = PhoneTurnPose(Offset.Zero, Size(100f, 100f), 0f)
            val target = source.copy(pivot = Offset(300f, 500f), rotation = 90f)
            turn.register("cover", token, source, 0)
            turn.begin(90)
            turn.register("cover", token, target, 90)
            val settlement = frames.scope.launch { turn.awaitSettled() }
            frames.advance(8)
            val locks = PhonePlayerMenuLocks(turn)
            val queue = Any()
            locks.set(queue, true)
            val paused = turn.currentPose("cover")
            frames.advance(8)
            assertEquals(paused, turn.currentPose("cover"))
            assertFalse(settlement.isCompleted)
            locks.set(queue, false)
            frames.advance(180)
            assertTrue(settlement.isCompleted)
            assertEquals(target, turn.currentPose("cover"))
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun lyricsCrossfadeRunsDuringBothTurnsWhileContentRebindsKeepTheOriginalTrajectory() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            for (rotation in listOf(90, -90)) {
                val turn = PhonePlayerTurn(frames.scope)
                val oldToken = Any()
                val targetToken = Any()
                val source = PhoneTurnPose(Offset(100f, 200f), Size(400f, 400f), 0f)
                val target = PhoneTurnPose(Offset(500f, 650f), Size(240f, 240f), rotation.toFloat())
                turn.register("cover", oldToken, source, 0)
                turn.begin(rotation)
                turn.register("cover", oldToken, target, rotation)
                val settle = frames.scope.launch { turn.awaitSettled() }
                frames.advance(8)
                val before = turn.currentPose("cover")!!
                turn.register("cover", targetToken, target, rotation)
                turn.unregister("cover", oldToken)
                assertEquals(before, turn.currentPose("cover"))
                assertTrue(lyricTrackUsesFade(true, false, false, true))
                val fade = LyricHeldTrackTransition()
                val a = LyricWindow(LyricPresentation("a", MusicSource.QQ, 30_000L, listOf(TimedLyric(0L, "a"))))
                val b = LyricWindow(LyricPresentation("b", MusicSource.QQ, 30_000L, listOf(TimedLyric(0L, "b"))))
                fade.request(a, b, frames.scope, gesturePreparation = false)
                b.ready.complete(Unit)
                frames.advance(6)
                assertTrue(fade.weight(b) > 0f && fade.weight(b) < 1f)
                assertTrue(turn.isTurning)
                assertTrue(kotlin.math.abs(turn.currentPose("cover")!!.rotation) > kotlin.math.abs(before.rotation))
                frames.advance(180)
                assertTrue(settle.isCompleted)
                assertFalse(turn.isTurning)
                assertEquals(target, turn.currentPose("cover"))
                assertFalse(fade.active)
                fade.cancel()
            }
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun cancelledMidTurnJobResumesFromItsVisiblePoseWhenContentChanges() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val turn = PhonePlayerTurn(frames.scope)
            val token = Any()
            val source = PhoneTurnPose(Offset(100f, 200f), Size(400f, 400f), 0f)
            val target = PhoneTurnPose(Offset(500f, 650f), Size(240f, 240f), 90f)
            turn.register("cover", token, source, 0)
            turn.begin(90)
            turn.register("cover", token, target, 90)
            frames.advance(8)
            frames.scope.coroutineContext.cancelChildren()
            yield()
            val stopped = turn.currentPose("cover")!!
            assertTrue(stopped.rotation > 0f && stopped.rotation < 90f)
            val settle = frames.scope.launch { turn.awaitSettled() }
            assertEquals(stopped, turn.currentPose("cover"))
            frames.advance(180)
            assertTrue(settle.isCompleted)
            assertFalse(turn.isTurning)
            assertEquals(target, turn.currentPose("cover"))
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun asynchronousBitmapCopyKeepsThePoseAndMomentumFromItsRecordedFrame() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            for (rotation in listOf(90, -90)) {
                val turn = PhonePlayerTurn(frames.scope)
                val token = Any()
                val start = PhoneTurnPose(Offset(100f, 200f), Size(400f, 200f), 0f)
                val landscape = start.copy(pivot = Offset(500f, 650f), rotation = rotation.toFloat())
                turn.register("lyrics", token, start, 0, scaleContent = false)
                turn.begin(rotation)
                turn.register("lyrics", token, landscape, rotation, scaleContent = false)
                frames.advance(8)
                val recorded = turn.currentPose("lyrics")!!
                turn.captureSourceFrame()
                frames.advance(6)
                assertNotEquals(recorded.pivot, turn.currentPose("lyrics")!!.pivot)
                turn.begin(0, prepare = true)
                val paragraphSize = Size(600f, 1000f)
                val baseline = phoneLyricsBaselineFraction(Offset(16f, 350f), Offset(40f, -180f), paragraphSize)!!
                val portrait = start.copy(size = paragraphSize, pivotFraction = baseline)
                turn.register("lyrics", token, portrait, 0, scaleContent = false)
                turn.releasePreparation()
                assertEquals(recorded.pivot, turn.currentPose("lyrics")!!.pivot)
                assertEquals(recorded.rotation, turn.currentPose("lyrics")!!.rotation, .001f)
                // 字形保持目标的自然排版尺寸，起点基线仍来自所见旧帧。
                assertEquals(paragraphSize, turn.currentPose("lyrics")!!.size)
                val before = turn.currentPose("lyrics")!!.pivot
                frames.advance(3)
                assertTrue("recorded=$recorded before=$before after=${turn.currentPose("lyrics")}",
                    turn.currentPose("lyrics")!!.pivot.x > before.x)
                val waiting = frames.scope.launch { turn.awaitSettled() }
                frames.advance(180)
                assertTrue(waiting.isCompleted)
                assertEquals(portrait, turn.currentPose("lyrics"))
            }
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun resetAndUnpreparedGesturesCannotReuseAnOldRecordedSource() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            for (reset in listOf(false, true)) {
                val turn = PhonePlayerTurn(frames.scope)
                val token = Any()
                val old = PhoneTurnPose(Offset(100f, 200f), Size(400f, 200f), 90f)
                turn.register("cover", token, old, 90)
                turn.captureSourceFrame()
                if (reset) turn.reset(90)
                val latest = old.copy(pivot = Offset(400f, 600f))
                turn.register("cover", token, latest, 90)
                turn.begin(0, prepare = reset)
                turn.register("cover", token, old.copy(rotation = 0f), 0)
                if (reset) turn.releasePreparation()
                assertEquals(latest.pivot, turn.currentPose("cover")!!.pivot)
            }
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun idleClockDrawingUsesItsActualLayoutEvenWhenTheRegisteredEndpointIsStale() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val turn = PhonePlayerTurn(frames.scope)
            val token = Any()
            val old = PhoneTurnPose(Offset(200f, -2000f), Size(600f, 200f), 90f)
            val actual = old.copy(pivot = Offset(350f, 500f), rotation = -90f)
            turn.register("clock", token, old, 90)
            turn.reset(-90)
            turn.register("clock", token, actual.copy(rotation = 0f), -90, physicalRotation = -90)
            assertFalse(turn.endpointReady("clock", -90))
            assertEquals(actual, turn.drawingPose("clock", actual, Offset.Zero))
            val moving = Offset(0f, 120f)
            assertEquals(actual.copy(pivot = actual.pivot + moving), turn.drawingPose("clock", actual, moving))
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun closedUnplacedLyricsCannotHoldTheTurnOrReplayARotationWhenOpenedLater() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            for (rotation in listOf(90, -90)) {
                val turn = PhonePlayerTurn(frames.scope)
                val token = Any()
                val old = PhoneTurnPose(Offset(100f, 200f), Size(300f, 500f), 0f)
                val target = old.copy(pivot = Offset(500f, 350f), rotation = rotation.toFloat())
                turn.register("lyrics", token, old, 0)
                turn.attachEndpoint("lyrics", token, 0) { }
                turn.updateHidden("lyrics", token, true)
                turn.begin(rotation)
                turn.attachEndpoint("lyrics", token, rotation) { }
                val waiting = frames.scope.launch { turn.awaitSettled() }
                frames.advance(4)
                assertTrue(waiting.isCompleted)
                assertFalse(turn.isTurning)
                turn.updateHidden("lyrics", token, false)
                turn.register("lyrics", token, target, rotation, physicalRotation = rotation)
                assertEquals(target, turn.currentPose("lyrics"))
                frames.advance(4)
                assertFalse(frames.hasAwaiters)
            }
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun closingLyricsDuringATurnCancelsOnlyTheirCompensationAndKeepsTheCoverMoving() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val turn = PhonePlayerTurn(frames.scope)
            val lyrics = Any()
            val old = PhoneTurnPose(Offset(100f, 200f), Size(200f, 100f), 0f)
            val target = old.copy(pivot = Offset(400f, 600f), rotation = 90f)
            turn.register("lyrics", lyrics, old, 0)
            turn.register("cover", Any(), old, 0)
            turn.begin(90)
            turn.attachEndpoint("lyrics", lyrics, 90) { }
            turn.register("lyrics", lyrics, target, 90)
            turn.register("cover", Any(), target, 90)
            frames.advance(6)
            turn.updateHidden("lyrics", lyrics, true)
            turn.updateHidden("lyrics", lyrics, false)
            turn.register("lyrics", lyrics, target, 90)
            assertEquals(target, turn.currentPose("lyrics"))
            assertTrue(turn.hasVisibleMotion(setOf("cover")))
            val waiting = frames.scope.launch { turn.awaitSettled() }
            frames.advance(180)
            assertTrue(waiting.isCompleted)
            assertEquals(target, turn.currentPose("lyrics"))
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun settlingWaitsForValidDelayedLayoutInsteadOfDiscardingItsSourcePose() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            for (rotation in listOf(90, -90)) {
                val turn = PhonePlayerTurn(frames.scope)
                val token = Any()
                val source = PhoneTurnPose(Offset(100f, 200f), Size(200f, 100f), 0f)
                val target = source.copy(pivot = Offset(400f, 600f), rotation = rotation.toFloat())
                turn.register("lyrics", token, source, 0)
                turn.begin(rotation)
                var ready = false
                turn.attachEndpoint("lyrics", token, rotation) {
                    turn.register("lyrics", token, if (ready) target else source, rotation,
                        physicalRotation = rotation)
                }
                val waiting = frames.scope.launch { turn.awaitSettled() }
                frames.advance(8)
                assertFalse(waiting.isCompleted)
                assertTrue(turn.isTurning)
                assertEquals(source, turn.currentPose("lyrics"))
                ready = true
                frames.advance(1)
                assertNotEquals(target, turn.currentPose("lyrics"))
                frames.advance(180)
                assertTrue(waiting.isCompleted)
                assertFalse(turn.isTurning)
                assertEquals(target, turn.currentPose("lyrics"))
            }
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun physicalClockEntranceUsesActualHostBasisForBothLandscapeDirections() {
        val physical = Offset(0f, 1400f)
        for (rotation in listOf(90f, -90f)) {
            val local = phoneTurnLocalOffset(physical, rotation)
            val restored = phonePlayerFlightRotate(local, Offset.Zero, rotation)
            assertEquals(physical.x, restored.x, .001f)
            assertEquals(physical.y, restored.y, .001f)
            val staleLocal = phoneTurnLocalOffset(physical, 0f)
            val wrong = phonePlayerFlightRotate(staleLocal, Offset.Zero, rotation)
            assertEquals(1400f, kotlin.math.abs(wrong.x), .001f)
        }
    }

    @Test fun clockRejectsStaleHostPoseAndDropsItsInvisiblePendingAfterValidRegistration() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val turn = PhonePlayerTurn(frames.scope)
            val token = Any()
            val position = Offset(300f, 400f)
            val old = PhoneTurnPose(position, Size(600f, 200f), 90f)
            turn.register("clock", token, old, 0, physicalRotation = 90, hidden = true)
            turn.begin(-90)
            val waiting = frames.scope.launch { turn.awaitEndpoint("clock", -90) }
            turn.register("clock", token, old.copy(rotation = 0f), -90, physicalRotation = -90, hidden = true)
            frames.advance(2)
            assertFalse(waiting.isCompleted)
            assertFalse(turn.endpointReady("clock", -90))
            val target = old.copy(rotation = -90f)
            turn.register("clock", token, target, -90, physicalRotation = -90, hidden = true)
            frames.advance(2)
            assertTrue(waiting.isCompleted)
            assertTrue(turn.endpointReady("clock", -90))
            assertEquals(target, turn.currentPose("clock"))
            assertFalse(turn.hasVisibleMotion(setOf("clock")))
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun staleUnregisteredMainEndpointDoesNotWaitForUnrelatedOffscreenAnimation() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val turn = PhonePlayerTurn(frames.scope)
            val cover = Any()
            val clock = Any()
            val start = PhoneTurnPose(Offset(10f, 20f), Size(100f, 100f), 0f)
            turn.register("cover", cover, start, 0)
            turn.register("clock", clock, start, 0)
            turn.begin(90)
            turn.unregister("cover", cover)
            turn.register("clock", clock, start.copy(pivot = Offset(100_000_000f, 20f)), 90)
            val waiting = frames.scope.launch { turn.awaitElementsSettled(setOf("cover", "missing")) }
            frames.advance(2)
            assertTrue(waiting.isCompleted)
            assertFalse(turn.hasVisibleMotion(setOf("cover", "missing")))
            assertTrue(turn.hasVisibleMotion(setOf("clock")))
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun clockCanEnterAfterMainVisibleMotionWithoutWaitingForOffscreenTail() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val turn = PhonePlayerTurn(frames.scope)
            val source = PhoneTurnPose(Offset(10f, 20f), Size(100f, 100f), 0f)
            val cover = Any()
            val clock = Any()
            turn.register("cover", cover, source, 0)
            turn.register("clock", clock, source, 0)
            turn.begin(90)
            turn.register("cover", cover, source.copy(pivot = Offset(310f, 420f), rotation = 90f), 90)
            turn.register("clock", clock, source.copy(pivot = Offset(100_000_000f, 20f)), 90)
            val waiting = frames.scope.launch { turn.awaitElementsVisibleSettled(setOf("cover")) }
            frames.advance(70)
            assertTrue(waiting.isCompleted)
            assertFalse(turn.hasVisibleMotion(setOf("cover")))
            assertTrue(turn.hasVisibleMotion(setOf("clock")))
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun menuAndGestureHoldOwnersReleaseIndependentlyAndMenuDoesNotRequestFrames() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val turn = PhonePlayerTurn(frames.scope)
            val token = Any()
            val source = PhoneTurnPose(Offset(10f, 20f), Size(100f, 100f), 0f)
            val target = source.copy(pivot = Offset(310f, 420f), rotation = 90f)
            turn.register("cover", token, source, 0)
            turn.begin(90)
            turn.register("cover", token, target, 90)
            val waiting = frames.scope.launch { turn.awaitSettled() }
            frames.advance(8)
            val menu = Any()
            turn.hold(menu)
            val frozen = turn.currentPose("cover")
            frames.advance(4)
            assertFalse(frames.hasAwaiters)
            turn.hold()
            frames.advance(2)
            assertTrue(frames.hasAwaiters)
            turn.resume()
            frames.advance(2)
            assertFalse(frames.hasAwaiters)
            assertEquals(frozen, turn.currentPose("cover"))
            assertFalse(waiting.isCompleted)
            turn.resume(menu)
            frames.advance(150)
            assertTrue(waiting.isCompleted)
            assertEquals(target, turn.currentPose("cover"))
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun menuResumeKeepsTheFrozenPoseAndDoesNotRestorePrePauseMomentum() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val turn = PhonePlayerTurn(frames.scope)
            val token = Any()
            val source = PhoneTurnPose(Offset.Zero, Size(100f, 100f), 0f)
            val target = source.copy(pivot = Offset(400f, 0f))
            turn.register("cover", token, source, 0)
            turn.begin(90)
            turn.register("cover", token, target, 90)
            frames.advance(8)
            turn.begin(0)
            turn.register("cover", token, source, 0)
            val menu = Any()
            turn.hold(menu)
            val frozen = requireNotNull(turn.currentPose("cover"))
            frames.advance(180)
            assertEquals(frozen, turn.currentPose("cover"))
            assertFalse(frames.hasAwaiters)
            turn.resume(menu)
            assertEquals(frozen, turn.currentPose("cover"))
            frames.advance(2)
            assertTrue(turn.currentPose("cover")!!.pivot.x < frozen.pivot.x)
            frames.advance(150)
            assertEquals(source, turn.currentPose("cover"))
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun openingUsesTheLatestLandscapeDirectionAndStillStartsWithLyricsClosed() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val presentation = PhonePlayerPresentation(frames.scope)
            presentation.isPhone = true
            for (rotation in listOf(90, -90, 0)) {
                presentation.latestRotation = rotation
                presentation.nativeOrientation = PhonePlayerNativeOrientation(rotation, true)
                presentation.landscapeLyrics.value = true
                presentation.prepareOpen()
                assertEquals(rotation, presentation.rotationDegrees)
                assertEquals(rotation, presentation.turn.targetRotation)
                assertEquals(rotation.toFloat(), presentation.actionRotation.value, 0f)
                assertFalse(presentation.portraitLyrics.value)
            }
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun repeatedReorientationKeepsMomentumWhileWaitingForEitherEndpoint() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val turn = PhonePlayerTurn(frames.scope)
            val token = Any()
            val portrait = PhoneTurnPose(Offset(100f, 200f), Size(62f, 62f), 0f)
            val landscape = PhoneTurnPose(Offset(400f, 600f), Size(240f, 240f), 90f)
            turn.register("cover", token, portrait, 0)
            turn.begin(90)
            turn.register("cover", token, landscape, 90)
            frames.advance(8)
            val before = requireNotNull(turn.currentPose("cover"))
            turn.begin(0)
            assertEquals(before, turn.currentPose("cover"))
            frames.advance(1)
            val bridging = requireNotNull(turn.currentPose("cover"))
            assertTrue(bridging.rotation > before.rotation)
            assertTrue(bridging.pivot.x > before.pivot.x)
            // 新方向还未布局又反向，桥接仍从当前速度继续，而不是恢复第一次抓取值。
            turn.begin(90)
            frames.advance(1)
            val repeated = requireNotNull(turn.currentPose("cover"))
            assertTrue(repeated.rotation > bridging.rotation)
            turn.register("cover", token, landscape, 90)
            frames.advance(1)
            val resumed = requireNotNull(turn.currentPose("cover"))
            assertTrue(resumed.rotation > repeated.rotation)

            turn.begin(0)
            frames.advance(1)
            val returning = requireNotNull(turn.currentPose("cover"))
            turn.register("cover", token, portrait, 0)
            assertEquals(returning, turn.currentPose("cover"))
            frames.advance(1)
            assertNotEquals(returning, turn.currentPose("cover"))
            val done = frames.scope.launch { turn.awaitSettled() }
            frames.advance(180)
            assertTrue(done.isCompleted)
            assertEquals(portrait, turn.currentPose("cover"))
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun grabbingTheInertiaBridgeResumesItsDecayedVelocityInsteadOfTheOldSpringVelocity() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val turn = PhonePlayerTurn(frames.scope)
            val token = Any()
            val portrait = PhoneTurnPose(Offset(100f, 200f), Size(62f, 62f), 0f)
            val landscape = PhoneTurnPose(Offset(400f, 600f), Size(240f, 240f), 90f)
            turn.register("cover", token, portrait, 0)
            turn.begin(90)
            turn.register("cover", token, landscape, 90)
            frames.advance(8)
            turn.begin(0)
            frames.advance(1)
            val firstBridge = turn.currentPose("cover")!!.rotation
            frames.advance(1)
            val heldRotation = turn.currentPose("cover")!!.rotation
            val dt = 16_666_667f / 1_000_000_000f
            val frequency = kotlin.math.sqrt(PHONE_PLAYER_TURN_STIFFNESS)
            val decay = kotlin.math.exp(-frequency * dt)
            val bridgeVelocity = (heldRotation - firstBridge) * frequency * decay / (1f - decay)
            turn.hold()
            turn.register("cover", token, portrait, 0)
            frames.advance(8)
            assertEquals(heldRotation, turn.currentPose("cover")!!.rotation, 0f)
            turn.resume()
            frames.advance(1)
            // Compose FloatSpringSpec 先把纳秒整除为毫秒；桥接仍使用完整帧时长。
            // 用同一求解时刻的临界阻尼解析解核对续接速度，不能混用 16ms 与 16.666667ms。
            val springSeconds = (16_666_667L / 1_000_000L) / 1000f
            val expected = (heldRotation + (bridgeVelocity + frequency * heldRotation) * springSeconds) *
                kotlin.math.exp(-frequency * springSeconds)
            assertEquals(expected, turn.currentPose("cover")!!.rotation, .001f)
            val done = frames.scope.launch { turn.awaitSettled() }
            frames.advance(180)
            assertTrue(done.isCompleted)
            assertEquals(portrait, turn.currentPose("cover"))
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun releasingBeforeTheNewEndpointExistsKeepsBridgingUntilItsNextFrameRegistration() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val turn = PhonePlayerTurn(frames.scope)
            val token = Any()
            val portrait = PhoneTurnPose(Offset(100f, 200f), Size(62f, 62f), 0f)
            val landscape = PhoneTurnPose(Offset(400f, 600f), Size(240f, 240f), 90f)
            turn.register("cover", token, portrait, 0)
            turn.begin(90)
            turn.register("cover", token, landscape, 90)
            frames.advance(8)
            turn.begin(0)
            turn.hold()
            turn.resume()
            frames.advance(1)
            val beforeRegistration = requireNotNull(turn.currentPose("cover"))
            assertTrue(turn.isTurning)
            turn.register("cover", token, portrait, 0)
            assertEquals(beforeRegistration, turn.currentPose("cover"))
            frames.advance(1)
            assertNotEquals(beforeRegistration, turn.currentPose("cover"))
            val done = frames.scope.launch { turn.awaitSettled() }
            frames.advance(180)
            assertTrue(done.isCompleted)
            assertEquals(portrait, turn.currentPose("cover"))
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun compactCoverCornerKeepsItsVisualPixelsThroughTurningAndFlightRegrab() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val turn = PhonePlayerTurn(frames.scope)
            val token = Any()
            val compactCorner = phoneArtworkCorner(1f) * 62f / 360f
            val compact = PhoneTurnPose(Offset(100f, 200f), Size(62f, 62f), 0f, corner = compactCorner)
            val landscape = PhoneTurnPose(Offset(400f, 600f), Size(240f, 240f), 90f, corner = 12f)
            turn.register("cover", token, compact, 0)
            turn.begin(90)
            turn.register("cover", token, landscape, 90)
            assertEquals(compactCorner, turn.currentPose("cover")!!.corner, .0001f)
            frames.advance(8)
            val current = requireNotNull(turn.currentPose("cover"))
            // 4dp 量化后小封面实际约 11.71px，低于横屏的 12px；不假定端点大小顺序。
            assertTrue(current.corner > minOf(landscape.corner, compact.corner) &&
                current.corner < maxOf(landscape.corner, compact.corner))
            val outline = PhoneTurnArtworkShape(current.corner / current.size.width)
                .createOutline(Size(360f, 360f), LayoutDirection.Ltr, Density(1f)) as Outline.Rounded
            assertEquals(current.corner, outline.roundRect.topLeftCornerRadius.x * current.size.width / 360f, .0001f)
            val flight = PhonePlayerFlightRotation(frames.scope)
            flight.beginTurnDrag(turn)
            flight.beginDrag(90)
            val heldCorner = phonePlayerFlightArtworkCorner(16f, 12f, .63f, flight.targetPose("cover"))
            flight.beginTurnDrag(turn)
            flight.beginDrag(90, .63f)
            assertEquals(heldCorner,
                phonePlayerFlightArtworkCorner(16f, 12f, .63f, flight.targetPose("cover")), 0f)
            flight.resumeTurn()
            val settled = frames.scope.launch { turn.awaitSettled() }
            frames.advance(180)
            assertTrue(settled.isCompleted)
            assertEquals(12f, turn.currentPose("cover")!!.corner, 0f)
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun tiltedSurfaceCoversAllHostCornersAndKeepsExactPortraitAndLandscapeEndpoints() {
        val host = Rect(30f, 70f, 730f, 1270f)
        val corners = listOf(host.topLeft, host.topRight, host.bottomLeft, host.bottomRight)
        for (angle in listOf(0f, 45f, -45f, 90f, -90f)) {
            val pose = phonePlayerFlightSurfacePose(host, angle)
            assertEquals(host.center, pose.pivot)
            assertEquals(angle, pose.rotation, 0f)
            for (corner in corners) {
                // 反转到表面本地坐标验证覆盖，不能只检查旋转后的外接矩形。
                val local = phonePlayerFlightRotate(corner, pose.pivot, -angle) - pose.pivot
                assertTrue("angle=$angle corner=$corner x=$local", kotlin.math.abs(local.x) <= pose.size.width / 2f + .001f)
                assertTrue("angle=$angle corner=$corner y=$local", kotlin.math.abs(local.y) <= pose.size.height / 2f + .001f)
            }
            if (angle == 0f) assertEquals(host.size, pose.size)
            if (kotlin.math.abs(angle) == 90f) assertEquals(Size(host.height, host.width), pose.size)
        }
    }

    @Test fun backFromEitherLandscapeReturnsToThePhysicalMiniPlayerWithoutAPortraitStage() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            for (rotation in listOf(90, -90)) {
            val presentation = PhonePlayerPresentation(frames.scope)
            presentation.isPhone = true
            presentation.latestRotation = rotation
            presentation.nativeOrientation = PhonePlayerNativeOrientation(rotation, true)
            presentation.prepareOpen()
            val landscape = PhoneTurnPose(Offset(400f, 600f), Size(240f, 240f), rotation.toFloat(), corner = 12f)
            presentation.turn.register("cover", Any(), landscape, rotation)
            var dismissed = 0
            presentation.requestDismiss { dismissed++ }
            assertEquals(1, dismissed)
            assertTrue(presentation.pendingDismissActive)
            assertEquals(rotation, presentation.rotationDegrees)
            assertEquals(rotation, presentation.turn.targetRotation)
            assertEquals(landscape, presentation.flight.targetPose("cover"))
            assertEquals(0f, presentation.flight.sideWeight, 0f)
            val motion = PageMotion(frames.scope, true, 320, 280)
            val host = Rect(0f, 0f, 800f, 1200f)
            val mini = Rect(20f, 1080f, 100f, 1160f)
            val destination = landscape.bounds()
            motion.updateHost(host)
            motion.sources["surface"] = MotionAnchor(mini)
            motion.targets["surface"] = MotionAnchor(host)
            assertTrue(motion.beginDrag())
            for (progress in listOf(1f, .5f, 0f)) {
                motion.dragTo(progress)
                val geometry = phonePlayerFlightGeometry(motion, presentation.flight, rotation, "cover", mini, destination)
                assertEquals(rotation * progress, geometry.rotation, .001f)
                if (progress == 0f) assertEquals(mini, geometry.bounds)
                if (progress == 1f) assertEquals(landscape.pivot, geometry.bounds.center)
            }
            // 手势仍可接管本次收起，但不存在稍后补执行的竖屏返回任务。
            presentation.cancelPendingDismissForGesture()
            assertFalse(presentation.pendingDismissActive)
            presentation.flight.complete()
            frames.advance(8)
            assertEquals(1, dismissed)
            }
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun lyricSelectionIsSharedWithinAnOpeningAndClearedForTheNextOpening() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val presentation = PhonePlayerPresentation(frames.scope)
            presentation.isPhone = true
            presentation.landscapeLyrics.value = true
            assertTrue(presentation.portraitLyrics.value)
            presentation.portraitLyrics.value = false
            assertFalse(presentation.landscapeLyrics.value)
            presentation.landscapeLyrics.value = true
            presentation.prepareOpen()
            assertFalse(presentation.portraitLyrics.value)
            assertFalse(presentation.landscapeLyrics.value)
            presentation.portraitLyrics.value = true
            assertTrue(presentation.landscapeLyrics.value)
            presentation.prepareOpen()
            assertFalse(presentation.portraitLyrics.value)
            assertFalse(presentation.landscapeLyrics.value)
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun clockRebindingInvalidatesObserversAndDiscardsTheOldPhysicalOffset() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val turn = PhonePlayerTurn(frames.scope)
            val base = PhoneTurnPose(Offset(100f, 200f), Size(80f, 30f), 0f)
            val oldToken = Any()
            val newToken = Any()
            turn.register("clock", oldToken, base, 0, physicalOffset = { Offset(0f, 40f) })
            val observed = mutableListOf<Offset?>()
            frames.scope.launch { snapshotFlow { turn.currentPose("clock")?.pivot }.collect { observed += it } }
            frames.advance(1)
            assertEquals(Offset(100f, 240f), observed.last())

            turn.unregister("clock", oldToken)
            frames.advance(1)
            assertEquals(base.pivot, observed.last())
            val offset = mutableStateOf(Offset(0f, 70f))
            turn.register("clock", newToken, base, 0, physicalOffset = { offset.value })
            frames.advance(1)
            assertEquals(Offset(100f, 270f), observed.last())

            // 旧实例迟到的销毁不能清除新实例的位移和观察关系。
            turn.unregister("clock", oldToken)
            offset.value = Offset(0f, 90f)
            frames.advance(1)
            assertEquals(Offset(100f, 290f), observed.last())
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun unscaledLyricsViewportSizeChangesDoNotKeepAnInvisibleTurnRunning() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val turn = PhonePlayerTurn(frames.scope)
            val token = Any()
            val pivot = Offset(100f, 300f)
            turn.register("lyrics", token, PhoneTurnPose(pivot, Size(300f, 900f), 0f), 0,
                scaleContent = false)
            turn.begin(90)
            turn.register("lyrics", token, PhoneTurnPose(pivot, Size(2000f, 200f), 0f), 90,
                scaleContent = false)
            val settled = frames.scope.launch { turn.awaitSettled() }
            frames.advance(4)
            assertTrue(settled.isCompleted)
            assertFalse(turn.isTurning)
            assertEquals(Size(2000f, 200f), turn.currentPose("lyrics")?.size)
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun holdingAMidTurnFreezesItsVisiblePoseAndResumingReachesTheSameEndpoint() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val turn = PhonePlayerTurn(frames.scope)
            val token = Any()
            val source = PhoneTurnPose(Offset(100f, 200f), Size(100f, 100f), 0f)
            val target = PhoneTurnPose(Offset(400f, 600f), Size(240f, 240f), 90f)
            turn.register("cover", token, source, 0)
            turn.begin(90)
            turn.register("cover", token, target, 90)
            val settled = frames.scope.launch { turn.awaitSettled() }
            frames.advance(8)
            val before = requireNotNull(turn.currentPose("cover"))
            assertTrue(before.rotation > 0f && before.rotation < 90f)
            turn.hold()
            assertEquals(before, turn.currentPose("cover"))
            frames.advance(8)
            assertEquals(before, turn.currentPose("cover"))
            assertFalse(settled.isCompleted)
            turn.resume()
            assertEquals(before, turn.currentPose("cover"))
            frames.advance(180)
            assertTrue(settled.isCompleted)
            assertFalse(turn.isTurning)
            assertEquals(target, turn.currentPose("cover"))
        } finally { coroutineContext.cancelChildren() }
    }

    @Test fun flightRegrabAtHalfProgressPreservesTheCurrentTiltAndSideSettlement() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val turn = PhonePlayerTurn(frames.scope)
            val pose = PhoneTurnPose(Offset(350f, 500f), Size(240f, 240f), 45f)
            turn.register("cover", Any(), pose, 0)
            val motion = PageMotion(frames.scope, true, 320, 280)
            motion.updateHost(Rect(0f, 0f, 700f, 1200f))
            val source = Rect(40f, 1080f, 100f, 1140f)
            val target = Rect(230f, 380f, 470f, 620f)
            motion.sources["cover"] = MotionAnchor(source)
            motion.targets["cover"] = MotionAnchor(target)
            assertTrue(motion.beginDrag())
            motion.dragTo(.5f)
            val flight = PhonePlayerFlightRotation(frames.scope)
            flight.updateTravel(1200f)
            flight.beginTurnDrag(turn)
            flight.beginDrag(0, .5f)
            val first = phonePlayerFlightGeometry(motion, flight, 0, "cover", source, target)
            assertEquals(22.5f, first.rotation, .0001f)
            assertEquals(motionRect(source, target, .5f), first.bounds)

            flight.dragTo(.25f)
            flight.resumeTurn()
            val settlement = frames.scope.launch { flight.settle(); flight.awaitTurn() }
            frames.advance(8)
            val before = phonePlayerFlightGeometry(motion, flight, 0, "cover", source, target)
            val weight = flight.sideWeight
            assertTrue(weight > 0f && weight < .5f)
            flight.beginTurnDrag(turn)
            flight.beginDrag(0, motion.value)
            settlement.cancelAndJoin()
            assertEquals(weight, flight.sideWeight, 0f)
            assertEquals(before, phonePlayerFlightGeometry(motion, flight, 0, "cover", source, target))
            frames.advance(4)
            assertEquals(before, phonePlayerFlightGeometry(motion, flight, 0, "cover", source, target))

            flight.resumeTurn()
            val resumed = frames.scope.launch { flight.settle(); flight.awaitTurn() }
            frames.advance(180)
            assertTrue(resumed.isCompleted)
            assertEquals(0f, flight.sideWeight, 0f)
            assertFalse(turn.isTurning)
        } finally { coroutineContext.cancelChildren() }
    }

    private class Frames(parent: CoroutineScope) {
        private val clock = BroadcastFrameClock()
        val hasAwaiters get() = clock.hasAwaiters
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
