package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricBackdropPixelEdgeTest {
    @Test fun 边缘落在整像素上() {
        assertEquals(503f, lyricBackdropPixelEdge(502.6f), 0f)
        assertEquals(502f, lyricBackdropPixelEdge(502.4f), 0f)
        assertEquals(-12f, lyricBackdropPixelEdge(-12.3f), 0f)
    }
}
