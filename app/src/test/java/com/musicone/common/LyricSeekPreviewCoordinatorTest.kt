package com.musicone

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LyricSeekPreviewCoordinatorTest {
    @Test fun cancelledUnpreparedPreviewKeepsTheActuallyDisplayedSource() = runBlocking {
        withContext(BroadcastFrameClock()) {
            val controller = LyricLayerTransitionController("track", 1)
            val source = controller.currentPane
            controller.beginTransition(30, 1f, 800f, this)
            val preview = launch { controller.previewTo(60) { _, _ -> } }
            repeat(3) { yield() }
            assertTrue(controller.previewPreparing)
            preview.cancelAndJoin()
            controller.finishPreview()
            assertSame(source, controller.currentPane)
            assertFalse(source.isFrozen)
            assertNull(controller.outgoingPane)
            assertNull(controller.previewPane)
            assertEquals(0f, controller.paneTranslation(source), 0f)
        }
    }

    @Test fun obsoletePreviewCleanupCannotClearTheNewPositionOwner() = runBlocking {
        val controller = LyricLayerTransitionController("track", 1)
        val previous = controller.attachPreviewOwner()
        controller.previewTo(3) { _, _ -> }
        val latest = controller.attachPreviewOwner()
        controller.previewTo(8) { _, _ -> }
        controller.finishPreview(previous)
        assertTrue(controller.previewActive)
        assertEquals(8, controller.currentPane.currentLine)
        controller.finishPreview(latest)
        assertFalse(controller.previewActive)
    }

    @Test fun layoutPreparationCancelsASeekWithoutLosingTheSourceOrOverwritingItsFocus() = runBlocking {
        withContext(BroadcastFrameClock()) {
            val controller = LyricLayerTransitionController("track", 1)
            val source = controller.currentPane
            val seek = controller.beginTransition(40, 1f, 800f, this)!!
            yield()
            controller.settleForLayout(6)
            seek.join()
            assertSame(source, controller.currentPane)
            assertEquals(6, source.currentLine)
            assertFalse(source.isFrozen)
            assertEquals(LyricLayerTransitionState.NORMAL, controller.state)
            assertEquals(0f, controller.drawingOffset, 0f)
            assertNull(controller.outgoingPane)
        }
    }

    @Test fun interruptedSlideKeepsActualOffsetUntilThePreviewHasDrawn() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            val controller = LyricLayerTransitionController("track", 1)
            controller.beginTransition(20, 1f, 800f, this)
            controller.currentPane.ready.complete(Unit)
            repeat(7) { yield(); clock.sendFrame((it + 1) * 16_666_667L); yield() }
            assertEquals(LyricLayerTransitionState.TRANSITIONING, controller.state)
            val offset = controller.seekOffset.value
            assertTrue(offset > 0f && offset < 800f)
            val source = controller.outgoingPane
            val previous = controller.currentPane
            val preview = launch { controller.previewTo(60) { _, _ -> } }
            repeat(3) { yield() }
            assertEquals(offset, controller.seekOffset.value, 0f)
            assertSame(source, controller.outgoingPane)
            assertSame(previous, controller.currentPane)
            assertEquals(20, previous.currentLine)
            val prepared = controller.previewPane!!
            controller.previewDrawn(prepared, 60, 0)
            preview.join()
            assertSame(prepared, controller.currentPane)
            assertNull(controller.outgoingPane)
            assertEquals(0f, controller.seekOffset.value, 0f)
            controller.finishPreview()
        }
    }

    @Test fun previewTakesOverWithoutLettingCancelledLayerTaskResetItsSource() = runBlocking {
        withContext(BroadcastFrameClock()) {
            val controller = LyricLayerTransitionController("track", 1)
            controller.beginTransition(20, 1f, 800f, this)
            yield()
            val source = controller.outgoingPane
            val target = controller.currentPane
            val preview = launch { controller.previewTo(35) { _, _ -> } }
            repeat(3) { yield() }
            val previewPane = controller.previewPane!!
            assertTrue(controller.previewPreparing)
            assertSame(source, controller.outgoingPane)
            assertEquals(LyricLayerTransitionState.PREPARING, controller.state)
            // 旧首帧就绪与错误窗口通知均不能提前交接。
            target.ready.complete(Unit)
            controller.previewDrawn(source!!, 35, 0)
            controller.previewDrawn(target, 35, 0)
            controller.previewDrawn(previewPane, 35, 12)
            yield()
            assertFalse(preview.isCompleted)
            controller.previewDrawn(previewPane, 35, 0)
            preview.join()
            assertEquals(LyricLayerTransitionState.NORMAL, controller.state)
            assertNull(controller.outgoingPane)
            assertEquals(35, controller.currentPane.currentLine)
            assertEquals(0f, controller.seekOffset.value, 0f)
            assertEquals(0f, controller.seekTravel, 0f)
            controller.finishPreview()
        }
    }

    @Test fun latestPreviewReplacesCancelledTargetAndBlocksPlaybackAndClickTakeover() = runBlocking {
        withContext(BroadcastFrameClock()) {
            val controller = LyricLayerTransitionController("track", 1)
            controller.beginTransition(20, 1f, 800f, this)
            yield()
            val pane = controller.currentPane
            val obsolete = launch { controller.previewTo(35) { _, _ -> } }
            repeat(3) { yield() }
            obsolete.cancelAndJoin()
            val latest = launch { controller.previewTo(60) { _, _ -> } }
            yield()
            val previewPane = controller.previewPane!!
            controller.updatePlaybackLine(2)
            controller.beginTransition(3, -1f, 800f, this)
            assertSame(pane, controller.currentPane)
            assertEquals(20, pane.currentLine)
            assertEquals(60, previewPane.currentLine)
            controller.previewDrawn(previewPane, 35, 0)
            yield()
            assertFalse(latest.isCompleted)
            controller.previewDrawn(previewPane, 60, 0)
            latest.join()
            controller.finishPreview()
            assertFalse(controller.previewActive)
            assertFalse(controller.previewPreparing)
            assertEquals(60, controller.currentPane.currentLine)
        }
    }

    @Test fun ordinaryPreviewReusesPaneAndDoesNotStartLayerTransition() = runBlocking {
        val controller = LyricLayerTransitionController("track", 1)
        val pane = controller.currentPane
        controller.updatePlaybackLine(2, present = false)
        assertEquals(1, pane.currentLine)
        val positions = mutableListOf<Int>()
        for (line in listOf(2, 80, 4)) controller.previewTo(line) { _, index -> positions += index }
        assertEquals(listOf(2, 80, 4), positions)
        assertSame(pane, controller.currentPane)
        assertNull(controller.outgoingPane)
        assertEquals(4, pane.currentLine)
        controller.finishPreview()
        assertEquals(LyricLayerTransitionState.NORMAL, controller.state)
        assertEquals(0f, controller.seekOffset.value, 0f)
    }
}
