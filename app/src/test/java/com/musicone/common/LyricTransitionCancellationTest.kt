package com.musicone

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class LyricTransitionCancellationTest {
    @Test fun immediateCancellationOfAnOrdinarySeekRestoresAnInteractiveSource() = runBlocking {
        val controller = LyricLayerTransitionController("song", 4)
        val source = controller.currentPane
        val operation = controller.beginTransition(40, 1f, 800f, this)!!
        assertTrue(source.isFrozen)
        operation.cancelAndJoin()
        assertSame(source, controller.currentPane)
        assertFalse(source.isFrozen)
        assertEquals(LyricLayerTransitionState.NORMAL, controller.state)
        assertNull(controller.outgoingPane)
        assertEquals(0f, controller.drawingOffset, 0f)
    }

    @Test fun cancellationWhileWaitingForTargetDrawingAlsoUnlocksTheSource() = runBlocking {
        val controller = LyricLayerTransitionController("song", 4)
        val source = controller.currentPane
        val operation = controller.beginTransition(40, 1f, 800f, this)!!
        val abandoned = controller.currentPane
        yield()
        operation.cancelAndJoin()
        abandoned.ready.complete(Unit)
        yield()
        assertSame(source, controller.currentPane)
        assertFalse(source.isFrozen)
        assertEquals(LyricLayerTransitionState.NORMAL, controller.state)
    }

    @Test fun obsoleteCancellationCannotUnfreezeOrReplaceTheNewRequest() = runBlocking {
        val controller = LyricLayerTransitionController("song", 4)
        val first = controller.beginTransition(40, 1f, 800f, this)!!
        val latest = controller.beginTransition(8, -1f, 800f, this)!!
        val target = controller.currentPane
        first.cancelAndJoin()
        assertSame(target, controller.currentPane)
        assertEquals(LyricLayerTransitionState.PREPARING, controller.state)
        assertTrue(controller.outgoingPane!!.isFrozen)
        latest.cancelAndJoin()
        assertFalse(controller.currentPane.isFrozen)
        assertEquals(LyricLayerTransitionState.NORMAL, controller.state)
    }
}
