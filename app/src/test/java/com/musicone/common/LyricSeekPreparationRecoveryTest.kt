package com.musicone

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LyricSeekPreparationRecoveryTest {
    @Test fun missingSeekGlyphReceiptUsesTheOriginalMotionAfterTheSharedDeadline() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            val controller = LyricLayerTransitionController("fixture", 0)
            var refreshed = 0
            controller.preparationDeadline = { 0L }
            controller.refreshDisplay = { refreshed++ }
            val operation = controller.beginTransition(8, 1f, 600f, this)!!
            val target = controller.currentPane
            repeat(180) { frame -> yield(); clock.sendFrame((frame + 1) * 16_666_667L); yield() }
            assertTrue(operation.isCompleted)
            assertTrue(controller.displayFallback)
            assertEquals(1, refreshed)
            assertSame(target, controller.currentPane)
            assertFalse(target.ready.isCompleted)
            assertFalse(target.isFrozen)
            assertEquals(LyricLayerTransitionState.NORMAL, controller.state)
        }
    }

    @Test fun hiddenPreviewCanRecoverWithoutLettingTheCancelledSeekPublishAgain() = runBlocking {
        val controller = LyricLayerTransitionController("fixture", 0)
        val original = controller.beginTransition(8, 1f, 600f, this)!!
        controller.preparationDeadline = { 0L }
        var enteredLayoutWait = false
        controller.previewTo(5, position = { _, _ -> enteredLayoutWait = true; awaitCancellation() })
        original.join()
        assertFalse(enteredLayoutWait)
        assertEquals(5, controller.currentPane.currentLine)
        assertTrue(controller.displayFallback)
        assertEquals(LyricLayerTransitionState.NORMAL, controller.state)
        assertFalse(controller.previewPreparing)
        controller.finishPreview()
        assertFalse(controller.previewActive)
    }
}
