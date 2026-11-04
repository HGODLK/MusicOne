package com.musicone

import org.junit.Assert.*
import org.junit.Test

class LyricDrawEpochTest {
    @Test fun seekReplacementAndPreviewDisposalRefreshReferencesButPlaybackFramesDoNot() {
        val epoch = LyricDrawEpoch()
        val controller = Any()
        val source = Any()
        epoch.observe(controller, listOf(source, null, null))
        val steady = epoch.revision
        repeat(120) { epoch.observe(controller, listOf(source, null, null)) }
        assertEquals(steady, epoch.revision)
        val target = Any()
        epoch.observe(controller, listOf(target, source, null))
        assertTrue(epoch.revision > steady)
        val seek = epoch.revision
        epoch.observe(controller, listOf(target, source, Any()))
        assertTrue(epoch.revision > seek)
        val preview = epoch.revision
        epoch.observe(controller, listOf(target, null, null))
        assertTrue(epoch.revision > preview)
    }
}
