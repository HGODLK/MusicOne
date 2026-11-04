package com.musicone

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PhoneTurnContinuityTest {
    @Test fun stagedDrawingSubscribesToTheReleaseAndEveryUnevenTurnFrame() = runBlocking {
        for (sourceRotation in listOf(90, -90)) {
            val frames = Frames(CoroutineScope(coroutineContext))
            val observer = SnapshotStateObserver { it() }
            observer.start()
            try {
                val turn = PhonePlayerTurn(frames.scope)
                // 真实打开播放器时会先把目标角度对齐到起始方向。
                turn.reset(sourceRotation)
                val token = Any()
                val source = PhoneTurnPose(Offset(500f, 400f), Size(600f, 900f), sourceRotation.toFloat())
                turn.register("lyrics", token, source, sourceRotation, scaleContent = false)
                turn.begin(0, prepare = true)
                turn.register("lyrics", token, source.copy(pivot = Offset(300f, 600f), rotation = 0f), 0,
                    scaleContent = false)
                turn.stagePreparation()
                val drawing = PhoneLyricsDrawingWindow(1700, 1500, turn)
                val fade = PhoneLyricsFadeTurn()
                fade.frame(PhoneLyricsFadeFrame(0f, 600f, 48f, 48f, 200f, 1f), false, 1f)
                fade.capture()
                val portrait = PhoneLyricsFadeFrame(1700f, 3500f, 48f, 48f, 2100f, 0f)
                var mask = portrait
                val node = Any()
                var invalidated = false
                val onChanged: (Any) -> Unit = { invalidated = true }
                fun draw() = observer.observeReads(node, onChanged) {
                    mask = fade.frame(portrait, turn.orientationChanging, drawing.progress())
                }
                // 先推进一次全局快照，否则后续变化通知不到这个观察者。
                Snapshot.registerApplyObserver { _, _ -> }.dispose()
                draw()
                assertEquals(1f, mask.strength, 0f)
                Snapshot.sendApplyNotifications()
                invalidated = false
                turn.releasePreparation()
                Snapshot.sendApplyNotifications()
                assertTrue("准备释放必须通知曾经画过起点遮罩的节点", invalidated)
                draw()
                for (gap in listOf(8L, 16L, 95L, 11L, 180L, 7L, 33L)) {
                    var before = 0f
                    observer.observeReads(node, onChanged) { before = drawing.progress() }
                    invalidated = false
                    frames.frame(gap)
                    if (drawing.progress() != before) assertTrue("转向进度变化必须请求遮罩重绘", invalidated)
                }
                val settling = frames.scope.launch { turn.awaitSettled() }
                var lastTurningStrength = mask.strength
                repeat(100) { index ->
                    draw()
                    if (turn.orientationChanging) lastTurningStrength = mask.strength
                    frames.frame(if (index % 3 == 0) 110L else 9L)
                }
                draw()
                assertTrue(settling.isCompleted)
                assertEquals(portrait, mask)
                assertTrue("撤下横屏渐隐前权重已连续收敛，不能留到最后一帧补亮", lastTurningStrength < .001f)
            } finally {
                observer.stop()
                observer.clear()
                frames.scope.cancel()
            }
        }
    }

    @Test fun aGestureAfterClosedLyricsRotationMovesTheCoverFromTheFirstDrag() = runBlocking {
        for (previousRotation in listOf(90, -90)) {
            val frames = Frames(CoroutineScope(coroutineContext))
            try {
                val turn = PhonePlayerTurn(frames.scope)
                val token = Any()
                val source = PhoneTurnPose(Offset(500f, 400f), Size(300f, 300f), previousRotation.toFloat())
                var target = source.copy(pivot = Offset(300f, 600f), rotation = 0f)
                turn.register("cover", token, source, previousRotation)
                turn.begin(0, prepare = true)
                turn.register("cover", token, target, 0)
                turn.releasePreparation()
                for (gap in listOf(8L, 16L, 11L)) frames.frame(gap)
                val motion = PhoneLyricsMotion(false, onMotionStart = {
                    turn.followLayoutMotion(PhonePlayerPresentation.BodyKeys)
                })
                motion.beginDrag()
                for (progress in listOf(.02f, .2f, .1f, .3f)) {
                    motion.dragTo(progress)
                    val before = turn.currentPose("cover")!!
                    val next = target.copy(pivot = target.pivot + Offset(0f, -12f))
                    turn.register("cover", token, next, 0)
                    assertEquals(-12f, turn.currentPose("cover")!!.pivot.y - before.pivot.y, .001f)
                    target = next
                }
                assertTrue(motion.dragging)
            } finally { frames.scope.cancel() }
        }
    }

    @Test fun openCloseTranslationCannotChangeTheRotationMaskProgress() = runBlocking {
        for (rotation in listOf(0, 90, -90)) {
            val frames = Frames(CoroutineScope(coroutineContext))
            try {
                val turn = PhonePlayerTurn(frames.scope)
                val token = Any()
                var shift = Offset.Zero
                val source = PhoneTurnPose(Offset(100f, 200f), Size(600f, 900f), if (rotation == 0) 90f else 0f)
                val target = source.copy(pivot = Offset(400f, 600f), rotation = rotation.toFloat())
                turn.register("lyrics", token, source, source.rotation.toInt(), physicalOffset = { shift }, scaleContent = false)
                turn.begin(rotation, prepare = true)
                turn.register("lyrics", token, target, rotation, physicalOffset = { shift }, scaleContent = false)
                turn.releasePreparation()
                val drawing = PhoneLyricsDrawingWindow(300, 400, turn)
                for (gap in listOf(8L, 95L, 11L, 33L, 7L)) {
                    frames.frame(gap)
                    shift = Offset.Zero
                    val progress = drawing.progress()
                    // 不推进时间，只改变开合位移；遮罩必须仍停在同一转向进度。
                    for (distance in listOf(7.25f, 300f, 921.2f, 1400f, 0f)) {
                        shift = if (rotation == 0) Offset(0f, distance) else Offset(distance, 0f)
                        assertEquals(progress, drawing.progress(), .0001f)
                    }
                }
            } finally { frames.scope.cancel() }
        }
    }

    @Test fun closingLyricsOwnsItsLayoutWhileTurnTailsKeepTheirOriginalMotion() = runBlocking {
        for (rotation in listOf(0, 90, -90)) {
            val frames = Frames(CoroutineScope(coroutineContext))
            try {
                val turn = PhonePlayerTurn(frames.scope)
                val token = Any()
                val source = PhoneTurnPose(Offset(100f, 200f), Size(300f, 500f),
                    if (rotation == 0) 90f else 0f)
                var target = source.copy(pivot = Offset(400f, 600f), rotation = rotation.toFloat())
                turn.register("cover", token, source, source.rotation.toInt())
                turn.register("play", token, source, source.rotation.toInt())
                turn.begin(rotation, prepare = true)
                turn.register("cover", token, target, rotation)
                // 按钮留有更长尾段，模拟主体已可交互但整轮转向还未退出的窗口。
                turn.register("play", token, target.copy(pivot = Offset(30_000f, 40_000f)), rotation)
                turn.releasePreparation()
                val settling = frames.scope.launch { turn.awaitSettled() }
                val gaps = listOf(8L, 16L, 95L, 12L, 33L)
                var frame = 0
                while (turn.hasVisibleMotion(PhonePlayerPresentation.BodyKeys) && frame < 100) {
                    frames.frame(gaps[frame++ % gaps.size])
                }
                assertFalse(turn.hasVisibleMotion(PhonePlayerPresentation.BodyKeys))
                assertTrue(turn.isTurning)
                val beforeRelease = turn.currentPose("cover")!!
                turn.followLayoutMotion(PhonePlayerPresentation.BodyKeys)
                assertEquals(beforeRelease, turn.currentPose("cover"))
                val lyrics = PhoneLyricsMotion(true)
                val closing = frames.scope.launch { lyrics.moveTo(false) }
                var previousProgress = 1f
                // 正常开合的布局每帧变化，不得再制造转向补偿并把关闭任务取消。
                repeat(80) { index ->
                    frames.frame(gaps[index % gaps.size])
                    val progress = lyrics.lyricsProgress
                    assertTrue(progress <= previousProgress + .0001f)
                    previousProgress = progress
                    val next = target.copy(pivot = Offset(400f, 600f + (1f - lyrics.headerProgress) * 700f))
                    val before = turn.currentPose("cover")!!
                    turn.register("cover", token, next, rotation)
                    val after = turn.currentPose("cover")!!
                    assertEquals(next.pivot.y - target.pivot.y, after.pivot.y - before.pivot.y, .01f)
                    assertFalse(turn.hasVisibleMotion(PhonePlayerPresentation.BodyKeys))
                    target = next
                }
                assertTrue(closing.isCompleted)
                assertEquals(0f, lyrics.lyricsProgress, 0f)
                assertTrue(settling.isCompleted)
                // 下一轮转向重新拥有端点，晚到布局仍必须保持画面连续。
                val nextRotation = if (rotation == 0) 90 else 0
                turn.begin(nextRotation, prepare = true)
                target = target.copy(rotation = nextRotation.toFloat())
                turn.register("cover", token, target, nextRotation)
                turn.releasePreparation()
                frames.frame(16)
                val before = turn.currentPose("cover")!!
                turn.register("cover", token, target.copy(pivot = target.pivot + Offset(50f, 60f)), nextRotation)
                assertEquals(before.bounds().top, turn.currentPose("cover")!!.bounds().top, .01f)
            } finally { frames.scope.cancel() }
        }
    }

    @Test fun lateEndpointsKeepTheVisiblePoseAcrossUnevenFramesAndBothLandscapeDirections() = runBlocking {
        for (rotation in listOf(90, -90, 0)) {
            val frames = Frames(CoroutineScope(coroutineContext))
            try {
                val turn = PhonePlayerTurn(frames.scope)
                val token = Any()
                val source = PhoneTurnPose(Offset(120f, 230f), Size(300f, 500f),
                    if (rotation == 0) 90f else 0f)
                var target = source.copy(pivot = Offset(500f, 650f), rotation = rotation.toFloat())
                turn.register("lyrics", token, source, source.rotation.toInt(), scaleContent = false)
                turn.begin(rotation, prepare = true)
                turn.register("lyrics", token, target, rotation, scaleContent = false)
                turn.releasePreparation()
                val settling = frames.scope.launch { turn.awaitSettled() }
                // 帧率反复变化，包含连续丢帧及恢复后的短帧；同一帧内再登记多次布局。
                for (gap in listOf(16L, 8L, 95L, 180L, 11L, 33L, 7L, 120L)) {
                    frames.frame(gap)
                    repeat(2) { index ->
                        val before = turn.currentPose("lyrics")!!
                        target = target.copy(pivot = target.pivot + Offset(21f, -13f),
                            pivotFraction = if (index == 0) Offset(.35f, .45f) else Offset(.5f, .5f))
                        turn.register("lyrics", token, target, rotation, scaleContent = false)
                        val after = turn.currentPose("lyrics")!!
                        assertEquals(before.bounds().left, after.bounds().left, .01f)
                        assertEquals(before.bounds().top, after.bounds().top, .01f)
                        assertEquals(before.bounds().right, after.bounds().right, .01f)
                        assertEquals(before.bounds().bottom, after.bounds().bottom, .01f)
                        assertEquals(before.rotation, after.rotation, .001f)
                    }
                }
                repeat(180) { frames.frame(if (it % 4 == 0) 65L else 11L) }
                assertTrue(settling.isCompleted)
                assertEquals(target, turn.currentPose("lyrics"))
            } finally { frames.scope.cancel() }
        }
    }

    @Test fun multipleLayoutCallbacksInOneFrameCannotInjectVelocityIntoTheNextTurn() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val turn = PhonePlayerTurn(frames.scope)
            val token = Any()
            var source = PhoneTurnPose(Offset(100f, 200f), Size(300f, 500f), 0f)
            turn.register("lyrics", token, source, 0, scaleContent = false)
            frames.frame(230)
            repeat(4) {
                source = source.copy(pivot = source.pivot + Offset(40f, -15f))
                turn.register("lyrics", token, source, 0, scaleContent = false)
            }
            turn.captureSourceFrame()
            turn.begin(90, prepare = true)
            val target = source.copy(rotation = 90f)
            turn.register("lyrics", token, target, 90, scaleContent = false)
            turn.releasePreparation()
            for (gap in listOf(8L, 160L, 12L, 70L, 9L, 220L)) {
                frames.frame(gap)
                // 只有角度需要转动，位置始终不应被布局登记的巨大差值带走。
                assertEquals(source.pivot.x, turn.currentPose("lyrics")!!.pivot.x, .01f)
                assertEquals(source.pivot.y, turn.currentPose("lyrics")!!.pivot.y, .01f)
            }
        } finally { frames.scope.cancel() }
    }

    @Test fun reversingAfterDroppedFramesKeepsTheCapturedSourceUntilTheNewStartIsReleased() = runBlocking {
        val frames = Frames(CoroutineScope(coroutineContext))
        try {
            val turn = PhonePlayerTurn(frames.scope)
            val token = Any()
            val portrait = PhoneTurnPose(Offset(100f, 250f), Size(300f, 500f), 0f)
            val landscape = portrait.copy(pivot = Offset(500f, 400f), rotation = -90f)
            turn.register("lyrics", token, portrait, 0, scaleContent = false)
            turn.begin(-90)
            turn.register("lyrics", token, landscape, -90, scaleContent = false)
            for (gap in listOf(8L, 100L, 12L)) frames.frame(gap)
            val recorded = turn.currentPose("lyrics")!!
            turn.captureSourceFrame()
            frames.frame(240)
            turn.begin(0, prepare = true)
            turn.register("lyrics", token, portrait, 0, scaleContent = false)
            turn.stagePreparation()
            for (gap in listOf(9L, 180L, 11L)) {
                frames.frame(gap)
                assertEquals(recorded, turn.currentPose("lyrics"))
            }
            turn.releasePreparation()
            assertEquals(recorded.pivot.x, turn.currentPose("lyrics")!!.pivot.x, .01f)
            assertEquals(recorded.pivot.y, turn.currentPose("lyrics")!!.pivot.y, .01f)
            assertEquals(recorded.rotation, turn.currentPose("lyrics")!!.rotation, .001f)
            val settling = frames.scope.launch { turn.awaitSettled() }
            repeat(100) { frames.frame(if (it % 3 == 0) 110L else 9L) }
            assertTrue(settling.isCompleted)
            assertEquals(portrait, turn.currentPose("lyrics"))
        } finally { frames.scope.cancel() }
    }

    @Test fun reanchoringPreservesTheVisibleRectangleAndItsInstantaneousVelocity() {
        for (angle in listOf(-90f, -35f, 0f, 75f, 90f)) {
            val pose = PhoneTurnPose(Offset(400f, 500f), Size(300f, 600f), angle)
            val fraction = Offset(.2f, .7f)
            val changed = phoneTurnReanchor(pose, fraction)
            val a = pose.bounds()
            val b = changed.bounds()
            assertEquals(a.left, b.left, .01f)
            assertEquals(a.top, b.top, .01f)
            assertEquals(a.right, b.right, .01f)
            assertEquals(a.bottom, b.bottom, .01f)
            val velocity = Offset(30f, -20f)
            val extentVelocity = Offset(10f, -8f)
            val predicted = phoneTurnReanchorVelocity(pose, fraction, velocity, extentVelocity, 45f)
            val dt = .001f
            val next = pose.copy(pivot = pose.pivot + velocity * dt,
                size = Size(pose.size.width + extentVelocity.x * dt, pose.size.height + extentVelocity.y * dt),
                rotation = pose.rotation + 45f * dt)
            val measured = (phoneTurnReanchor(next, fraction).pivot - changed.pivot) / dt
            assertEquals(predicted.x, measured.x, .2f)
            assertEquals(predicted.y, measured.y, .2f)
        }
    }

    private class Frames(parent: CoroutineScope) {
        private val clock = BroadcastFrameClock()
        val scope = CoroutineScope(parent.coroutineContext + Job(parent.coroutineContext[Job]) + clock)
        private var time = 0L
        suspend fun frame(gapMillis: Long) {
            yield()
            time += gapMillis * 1_000_000L
            clock.sendFrame(time)
            Snapshot.sendApplyNotifications()
            yield()
        }
    }
}
