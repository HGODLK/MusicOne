package com.musicone.demo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QqRecentPlayRefreshGateTest {
    @Test fun hiddenChangesMergeUntilAConsumerReturns() {
        val gate = QqRecentPlayRefreshGate()
        repeat(3) { gate.invalidate() }
        assertFalse(gate.consumePending())
        gate.setVisible(Any(), true)
        assertTrue(gate.consumePending())
        assertFalse(gate.consumePending())
    }

    @Test fun removingOnePageKeepsTheOtherConsumerActive() {
        val gate = QqRecentPlayRefreshGate()
        val home = Any()
        val library = Any()
        gate.setVisible(home, true)
        gate.setVisible(library, true)
        assertTrue(gate.consumePending())
        gate.setVisible(home, false)
        assertTrue(gate.visible)
        gate.invalidate()
        assertTrue(gate.consumePending())
        gate.setVisible(library, false)
        assertFalse(gate.visible)
    }

    @Test fun hidingAllConsumersPreservesAnInvalidatedLoadForReturn() {
        val gate = QqRecentPlayRefreshGate()
        val page = Any()
        gate.setVisible(page, true)
        assertTrue(gate.consumePending())
        gate.setVisible(page, false)
        gate.invalidate()
        assertFalse(gate.consumePending())
        gate.setVisible(page, true)
        assertTrue(gate.consumePending())
    }

    @Test fun ordinaryReturnDoesNotReloadAlreadyConsumedContent() {
        val gate = QqRecentPlayRefreshGate()
        val page = Any()
        gate.setVisible(page, true)
        assertTrue(gate.consumePending())
        gate.setVisible(page, false)
        gate.setVisible(page, true)
        assertFalse(gate.consumePending())
        gate.invalidate()
        assertTrue(gate.consumePending())
    }
}
