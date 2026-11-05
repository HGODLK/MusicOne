package com.musicone

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiGlassMenuPlacementTest {
    private val host = Rect(0f, 0f, 400f, 900f)

    @Test fun narrowButtonAlignsMenuToItsRightEdgeBelow() {
        val button = Rect(330f, 20f, 378f, 68f)
        val offset = aiGlassMenuOffset(button, host, IntSize(360, 200), 8f, 12f)
        assertEquals(18f, offset.x)
        assertEquals(76f, offset.y)
    }

    @Test fun wideButtonCentersMenuUnderTheButton() {
        val button = Rect(16f, 300f, 384f, 348f)
        val offset = aiGlassMenuOffset(button, host, IntSize(300, 200), 8f, 12f)
        assertEquals(50f, offset.x)
        assertEquals(356f, offset.y)
    }

    @Test fun noRoomBelowFlipsMenuAboveTheButton() {
        val button = Rect(16f, 780f, 384f, 828f)
        val offset = aiGlassMenuOffset(button, host, IntSize(300, 200), 8f, 12f)
        assertEquals(572f, offset.y)
        assertTrue(offset.y + 200f <= button.top)
    }

    @Test fun menuTallerThanHostStaysInsideTopMargin() {
        val button = Rect(330f, 700f, 378f, 748f)
        val offset = aiGlassMenuOffset(button, host, IntSize(360, 1000), 8f, 12f)
        assertEquals(12f, offset.y)
        assertEquals(18f, offset.x)
    }

    @Test fun menuKeepsLeftMarginWhenButtonTouchesScreenEdge() {
        val button = Rect(0f, 20f, 40f, 68f)
        val offset = aiGlassMenuOffset(button, host, IntSize(360, 200), 8f, 12f)
        assertEquals(12f, offset.x)
    }
}
