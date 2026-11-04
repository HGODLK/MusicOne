package com.musicone

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class PhoneLyricsDrawingWindowTest {
    @Test fun retainedRotationRowsMatchTheOrdinaryExitInsteadOfEnteringFromAbove() {
        val drawing = PhoneLyricsDrawingWindow(1700, 1500)
        val ordinaryHeight = 2800f
        val original = LyricBoundaryWindow(360f, offset = { 0f })
        for (shift in listOf(0f, 12.5f, 359f, 360f, 900f, 1800f, 2662f, 2800f)) {
            val expanded = drawing.edges(ordinaryHeight + drawing.top + drawing.bottom, original, shift)
            val ordinary = LyricBoundaryViewport(LyricBoundaryWindow(original.topBuffer, { shift }),
                { ordinaryHeight }).read()
            assertEquals(ordinary.top, expanded.top - drawing.top, .001f)
            assertEquals(ordinary.bottom, expanded.bottom - drawing.top, .001f)
            for (rowTop in listOf(-1600f, -500f, -20f, 0f, 40f, 350f, 1000f, 2400f)) {
                assertEquals(lyricBoundaryLineAlpha(rowTop, rowTop + 60f, ordinary.top, ordinary.bottom, 8f),
                    lyricBoundaryLineAlpha(rowTop + drawing.top, rowTop + drawing.top + 60f,
                        expanded.top, expanded.bottom, 8f), .0001f)
            }
        }
    }

    @Test fun theLastDrawableRowActuallyPassesTheStageBottomBeforeClosing() {
        for (stageHeight in listOf(1080, 2200)) for (drawingTop in listOf(900, 1700, 2600)) {
            val header = 258
            val extension = 360
            val travel = phoneLyricsWindowTravel(stageHeight, header, extension, extension)
            val drawing = PhoneLyricsDrawingWindow(drawingTop, 1500)
            val ordinaryHeight = stageHeight - header + extension * 2
            val closed = drawing.edges((ordinaryHeight + drawing.top + drawing.bottom).toFloat(),
                LyricBoundaryWindow(extension.toFloat(), { travel }), travel)
            val sourceOrigin = header - extension * 2 - drawing.top + travel
            assertTrue(sourceOrigin + closed.top >= stageHeight)
            assertTrue(closed.bottom <= closed.top)
        }
    }

    @Test fun movingGlyphsCrossTheSameScreenBoundaryThroughoutTheTurn() {
        val drawing = PhoneLyricsDrawingWindow(1500, 1700)
        val height = 5000f
        for (progress in listOf(0f, .13f, .47f, .98f, 1f)) {
            val resting = drawing.viewport(height, 0f, progress)
            for (shift in listOf(0f, 7.25f, 300.5f, 921.2f, 1400f)) {
                val actual = drawing.viewport(height, shift, progress)
                assertEquals(resting.top, actual.top + shift, .001f)
                assertEquals(resting.bottom, actual.bottom + shift, .001f)
                // 文字仍完整位于屏内时不能提前透明，真正越过底边后才剔除。
                val onScreenTop = resting.bottom - 24f
                val rowTop = onScreenTop - shift
                assertEquals(1f, lyricBoundaryLineAlpha(rowTop, rowTop + 16f,
                    actual.top, actual.bottom, 8f, partialTop = true), 0f)
                assertEquals(0f, lyricBoundaryLineAlpha(actual.bottom + .1f, actual.bottom + 16f,
                    actual.top, actual.bottom, 8f, partialTop = true), 0f)
            }
        }
    }

    @Test fun closingAfterTurnSettlementKeepsTheExpandedViewportUntilTheExitFinishes() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            var time = 0L
            suspend fun frame(gap: Long) {
                yield()
                time += gap * 1_000_000L
                clock.sendFrame(time)
                Snapshot.sendApplyNotifications()
                yield()
            }
            val retention = PhoneLyricsDrawingRetention()
            val drawing = PhoneLyricsDrawingWindow(1500, 1700)
            val motion = PhoneLyricsMotion(true)
            motion.dragTravelPx = 1400f
            assertSame(drawing, retention.resolve(drawing, false, true))
            val closing = launch { motion.moveTo(false) }
            val gaps = listOf(8L, 16L, 95L, 11L, 33L, 7L)
            repeat(120) { index ->
                frame(gaps[index % gaps.size])
                val moving = !motion.settledForRotation(false)
                // 转向在开合途中退出；源列表高度、阅读比例及裁剪坐标仍使用同一个窗口。
                val actual = retention.resolve(PhoneLyricsDrawingWindow.None, moving, true)
                if (moving) {
                    assertSame(drawing, actual)
                    assertEquals(1800, actual.logicalHeight(5000))
                } else assertSame(PhoneLyricsDrawingWindow.None, actual)
            }
            assertTrue(closing.isCompleted)
            assertEquals(0f, motion.lyricsProgress, 0f)
            assertEquals(1400f, motion.windowOffsetPx, 0f)
            assertSame(PhoneLyricsDrawingWindow.None, retention.resolve(PhoneLyricsDrawingWindow.None, false, true))
        }
    }

    @Test fun reversingTheCloseKeepsItsViewportAndHiddenPagesReleaseItImmediately() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            var time = 0L
            suspend fun frame(gap: Long) {
                yield()
                time += gap * 1_000_000L
                clock.sendFrame(time)
                Snapshot.sendApplyNotifications()
                yield()
            }
            val retention = PhoneLyricsDrawingRetention()
            val drawing = PhoneLyricsDrawingWindow(900, 1200)
            val motion = PhoneLyricsMotion(true)
            retention.resolve(drawing, false, true)
            val closing = launch { motion.moveTo(false) }
            for (gap in listOf(8L, 100L, 12L)) frame(gap)
            assertTrue(motion.lyricsProgress > 0f && motion.lyricsProgress < 1f)
            closing.cancelAndJoin()
            val before = motion.windowOffsetPx
            val reopening = launch { motion.moveTo(true) }
            yield()
            assertEquals(before, motion.windowOffsetPx, .001f)
            repeat(100) { index ->
                frame(if (index % 3 == 0) 90 else 11)
                val moving = !motion.settledForRotation(true)
                assertSame(if (moving) drawing else PhoneLyricsDrawingWindow.None,
                    retention.resolve(PhoneLyricsDrawingWindow.None, moving, true))
            }
            assertTrue(reopening.isCompleted)
            assertEquals(1f, motion.lyricsProgress, 0f)
            assertSame(PhoneLyricsDrawingWindow.None, retention.resolve(drawing, true, false))
            // 普通开合不会自行申请转屏补行；新的方向则换用本轮真实窗口。
            assertSame(PhoneLyricsDrawingWindow.None, retention.resolve(PhoneLyricsDrawingWindow.None, true, true))
            val next = PhoneLyricsDrawingWindow(1200, 800)
            assertSame(next, retention.resolve(next, true, true))
        }
    }

    @Test fun drawingBufferCoversBothAspectRatiosAndBothSidesOfTheReadingAnchor() {
        val window = Size(1080f, 2400f)
        val diagonal = hypot(window.width, window.height)
        for ((height, anchor) in listOf(1800 to 600f, 900 to 300f)) {
            val (top, bottom) = phoneLyricsDrawingInsets(window, height, anchor)
            assertTrue(top + anchor >= diagonal)
            assertTrue(bottom + height - anchor >= diagonal)
            assertEquals(top to bottom, phoneLyricsDrawingInsets(Size(2400f, 1080f), height, anchor))
        }
    }

    @Test fun expandedAndRestingLayoutsKeepTheSameReadingAnchorAndManualOffset() {
        val drawing = PhoneLyricsDrawingWindow(1500, 1700)
        val density = Density(3f)
        val bounds = LyricReadingBounds(120f, 240f)
        val original = lyricReadingAnchorDp(600f, bounds, density, 56f) * density.density
        val expanded = lyricReadingAnchorDp(5000f / 3f, drawing.bounds(bounds), density, 56f) * density.density
        assertEquals(original, expanded - drawing.top, .001f)
        val position = LyricReadingPosition(7, 100f, 900, following = false)
        assertEquals(200, position.targetOffset(drawing.logicalHeight(5000)))
        assertEquals(position.targetOffset(1800), position.targetOffset(drawing.logicalHeight(5000)))
    }

    @Test fun preparationRetainsExpandedEdgesAndCleanupMatchesTheRestingBoundary() = runBlocking {
        val turn = PhonePlayerTurn(this)
        for (rotation in listOf(90, -90, 0)) {
            turn.begin(rotation, prepare = true)
            val drawing = PhoneLyricsDrawingWindow(300, 400, turn)
            val boundary = LyricBoundaryWindow(40f, offset = { 25f })
            assertEquals(LyricBoundaryEdges(0f, 1475f), drawing.edges(1500f, boundary, 25f))
            turn.finishPreparationWithoutMotion()
            val expanded = drawing.edges(1500f, boundary, 25f)
            val resting = LyricBoundaryViewport(boundary, { 800f }).read()
            assertEquals(resting.top, expanded.top - drawing.top, 0f)
            assertEquals(resting.bottom, expanded.bottom - drawing.top, 0f)
        }
    }

    @Test fun gestureHoldsDoNotAcquireAnOrientationDrawingWindow() = runBlocking {
        val turn = PhonePlayerTurn(this)
        turn.hold(Any())
        assertTrue(turn.isTurning)
        assertFalse(turn.orientationChanging)
        turn.reset()
        turn.begin(90, prepare = true)
        assertTrue(turn.orientationChanging)
        turn.finishPreparationWithoutMotion()
        turn.hold(Any())
        assertFalse(turn.orientationChanging)
        turn.reset(-90)
        assertFalse(turn.orientationChanging)
    }

    @Test fun controlsPreparationResetsHiddenTargetAfterSavingItsOffscreenSource() = runBlocking {
        for (rotation in listOf(90, -90)) {
            val controls = PlayerControlsVisibilityState(this, initiallyHidden = true)
            controls.updateTravel(624f)
            val turn = PhonePlayerTurn(this)
            val token = Any()
            val pose = PhoneTurnPose(Offset(500f, 2200f), Size(1000f, 58f), 0f)
            val offset = { Offset(0f, playerControlsTranslation(controls.progress, controls.travelPx)) }
            turn.register("fixedActions", token, pose, 0, physicalOffset = offset, scaleContent = false)
            val source = turn.currentPose("fixedActions")!!
            assertEquals(2824f, source.pivot.y, 0f)
            turn.captureSourceFrame()
            turn.begin(rotation, prepare = true)
            controls.prepareRotation()
            turn.register("fixedActions", token, pose, rotation, physicalOffset = offset, scaleContent = false)
            assertEquals(source, turn.currentPose("fixedActions"))
            assertEquals(pose, turn.basePose("fixedActions"))
            assertEquals(0f, controls.velocity, 0f)
            assertFalse(controls.hidden)
            turn.finishPreparationWithoutMotion()
            assertEquals(pose, turn.currentPose("fixedActions"))
        }
    }
}
