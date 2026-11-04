package com.musicone

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test

class LyricStableFramesTest {
    @Test fun validButMovingEndpointsNeverCountAsStable() {
        val stable = LyricStableFrames()
        for (y in 0..12) assertFalse(stable.observe(Offset(20f, y.toFloat())))
        assertFalse(stable.observe(Offset(20f, 12f)))
        assertTrue(stable.observe(Offset(20f, 12f)))
        assertFalse(stable.observe(null))
        assertFalse(stable.observe(Offset(20f, 12f)))
    }

    @Test fun lyricNativeGeometryReplaysScaleAndMovementWithoutMatrixReads() {
        val native = Offset(8f, 44f)
        val pivot = Offset(0f, 120f)
        for (scale in listOf(.8695f, .925f, .97f, 1f)) for (shift in listOf(-400f, 0f, 150f)) {
            val drawn = lyricDrawnTextOrigin(native, pivot, scale, shift)
            val restored = lyricNativeTextOrigin(drawn, pivot, scale, shift)
            assertEquals(native.x, restored.x, .001f)
            assertEquals(native.y, restored.y, .001f)
            val next = lyricDrawnTextOrigin(restored, pivot, 1f, 0f)
            assertEquals(native.x, next.x, .001f)
            assertEquals(native.y, next.y, .001f)
        }
    }
}
