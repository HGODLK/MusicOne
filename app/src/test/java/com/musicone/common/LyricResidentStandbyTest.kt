package com.musicone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricResidentStandbyTest {
    private val owner = Any()
    private val frame = LyricStandbyFrame("stamp", 3)

    private fun ready(live: LyricStandbyFrame? = frame) = LyricResidentStandby().apply {
        bind(owner) { live }
        confirmOpening(1)
        drawn(owner, frame)
    }

    @Test fun confirmedAndDrawnAtTheSameFrameCanOpenDirectly() {
        assertTrue(ready().ready(1))
    }

    @Test fun firstOpenAfterATurnUsesThePreparationPath() {
        assertFalse(ready().ready(2))
        val fresh = LyricResidentStandby().apply { bind(owner) { frame }; drawn(owner, frame) }
        assertFalse(fresh.ready(0))
    }

    @Test fun lineOrLayoutChangedAfterTheLastDrawUsesThePreparationPath() {
        assertFalse(ready(LyricStandbyFrame("stamp", 4)).ready(1))
        assertFalse(ready(LyricStandbyFrame("rotated", 3)).ready(1))
        assertFalse(ready(null).ready(1))
    }

    @Test fun clearedOrUnalignedDrawUsesThePreparationPath() {
        assertFalse(ready().apply { clearDrawn() }.ready(1))
        assertFalse(ready().apply { drawn(owner, null) }.ready(1))
    }

    @Test fun releasedWindowCannotAuthorizeANewOwner() {
        val standby = ready()
        standby.bind(Any()) { frame }
        assertFalse(standby.ready(1))
        val other = ready()
        other.release(Any())
        assertTrue(other.ready(1))
        other.release(owner)
        assertFalse(other.ready(1))
    }
}
