package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class PlayerLyricsTopGlassTest {
    @Test fun fullBlurEdgeUsesExpandedSmallCoverAndFocusRemainsClear() {
        for (density in listOf(1f, 2.75f, 4f)) for (status in listOf(0f, 24f, 48f)) {
            val header = 86f * density
            val extension = (86f + 44f + status) * density
            val coverBottom = 70f * density
            val glass = phoneLyricsTopGlass(header, extension, coverBottom, 52f * density, 8f * density)
            val drawingTop = header - 2f * extension
            assertEquals(coverBottom, drawingTop + glass.blurStart, .001f)
            assertEquals(130f * density, drawingTop + glass.clearStart, .001f)
            assertTrue(drawingTop + glass.clearStart < header + 52f * density)
            // 上一句较低的部分仍处于交接带，不会整句完全模糊。
            assertTrue(110f * density in (drawingTop + glass.blurStart)..(drawingTop + glass.clearStart))
        }
    }

    @Test fun maskUsesTheSameDrawingOriginAsItsGradient() {
        val glass = phoneLyricsTopGlass(86f, 154f, 70f, 52f, 8f)
        val fraction = glass.maskFraction(154f)
        assertEquals(glass.blurStart, 154f + fraction * (glass.clearStart - 154f), .001f)
        assertEquals(60f, glass.clearStart - glass.blurStart, 0f)
    }
}
