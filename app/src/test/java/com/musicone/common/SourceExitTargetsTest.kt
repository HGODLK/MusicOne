package com.musicone

import com.musicone.sourceintegration.SourceExitTargets
import org.junit.Assert.*
import org.junit.Test

class SourceExitTargetsTest {
    @Test fun onlyTheRequestingSourcesTopSurfaceReceivesExit() {
        val targets = SourceExitTargets()
        val calls = mutableListOf<String>()
        targets.register("a") { calls += "a-root" }
        targets.register("b") { calls += "b" }
        val overlay = targets.register("a") { calls += "a-overlay" }
        assertTrue(targets.exit("a"))
        assertEquals(listOf("a-overlay"), calls)
        overlay.close()
        assertTrue(targets.exit("a"))
        assertFalse(targets.exit("removed"))
        assertEquals(listOf("a-overlay", "a-root"), calls)
    }

    @Test fun disposingAnOldSurfaceCannotUnregisterItsReplacement() {
        val targets = SourceExitTargets()
        var calls = 0
        val old = targets.register("a") { fail("旧页面不应再接收退出") }
        val current = targets.register("a") { calls++ }
        old.close()
        old.close()
        assertTrue(targets.exit("a"))
        assertEquals(1, calls)
        current.close()
        assertFalse(targets.exit("a"))
    }
}
