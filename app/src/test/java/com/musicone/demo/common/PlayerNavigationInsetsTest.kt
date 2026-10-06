package com.musicone.demo

import androidx.compose.ui.Modifier
import org.junit.Assert.*
import org.junit.Test

class PlayerNavigationInsetsTest {
    @Test fun gestureAndHiddenNavigationKeepOriginalModifier() {
        for (navigation in listOf(0, 24, 48, 80)) {
            val extension = buttonNavigationBottomExtension(navigation, 0)
            assertEquals(0, extension)
            assertSame(Modifier, Modifier.playerLyricsDrawExtension(extension))
        }
    }

    @Test fun buttonNavigationUsesActualBottomInset() {
        assertEquals(48, buttonNavigationBottomExtension(48, 48))
        assertEquals(96, buttonNavigationBottomExtension(96, 80))
        assertEquals(0, buttonNavigationBottomExtension(0, 48))
        assertEquals(0, buttonNavigationBottomExtension(0, 0))
    }

    @Test fun extendedDrawingKeepsBlurAlignedThroughoutLyricsAndControlsMotion() {
        for (height in listOf(480f, 800f, 1280f)) {
            for (reveal in listOf(0f, .25f, .6f, 1f)) {
                for (hidden in listOf(0f, .5f, 1f)) {
                    val original = height * reveal - 208f + playerControlsTranslation(hidden, 208f)
                    assertEquals(original, playerControlsBlurBoundary(height, 0, reveal, 208f, hidden), 0f)
                    for (extension in listOf(48, 96, 144)) {
                        assertEquals(original, playerControlsBlurBoundary(height + extension,
                            extension, reveal, 208f, hidden), .001f)
                    }
                }
            }
        }
    }

    @Test fun lyricsExitUsesFullBlurBeforeTheFixedCropWouldExposeClearText() {
        assertFalse(playerControlsNeedsExitBlur(304f, 250f))
        assertFalse(playerControlsNeedsExitBlur(250f, 250f))
        assertTrue(playerControlsNeedsExitBlur(249f, 250f))
        assertTrue(playerControlsNeedsExitBlur(-40f, 250f))
    }
}
