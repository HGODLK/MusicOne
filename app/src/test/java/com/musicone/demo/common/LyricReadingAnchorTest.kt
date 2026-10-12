package com.musicone.demo

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricReadingAnchorTest {
    @Test fun phoneAndTabletUseTheSameThirdOfTheirOwnReadableRegion() {
        val phone = adaptiveLyricReadingAnchor(900f, 300f, 720f, 72f, 8f)
        val tablet = adaptiveLyricReadingAnchor(700f, 24f, 676f, 88f, 8f)
        assertEquals(406.67f, phone, .01f)
        assertEquals(200f, tablet, .01f)
    }

    @Test fun shortViewportKeepsTheFocusedParagraphClearOfTheTopFadeAndControls() {
        val top = adaptiveLyricReadingAnchor(480f, 300f, 392f, 72f, 8f)
        assertTrue(top >= 308f)
        assertTrue(top + 72f <= 384f)
    }

    @Test fun fontScaleDoesNotChangeLyricPixelsButDisplayDensityDoes() {
        val largeFont = lyricDisplayDensity(Density(3f, 1.6f))
        val smallFont = lyricDisplayDensity(Density(3f, .85f))
        assertEquals(largeFont.density, smallFont.density, 0f)
        assertEquals(largeFont.fontScale, smallFont.fontScale, 0f)
        assertEquals(1f, largeFont.fontScale, 0f)
        assertEquals(4f, lyricDisplayDensity(Density(4f, 1.6f)).density, 0f)
    }

    @Test fun columnWidthOnlySlightlyAdjustsTheCurrentVisualSize() {
        val narrow = lyricTypographyForWidth(PlayerLayoutMode.COMPACT_SINGLE, 300f)
        val reference = lyricTypographyForWidth(PlayerLayoutMode.COMPACT_SINGLE, 340f)
        val wide = lyricTypographyForWidth(PlayerLayoutMode.COMPACT_SINGLE, 420f)
        assertTrue(narrow.activeSp in 30f..32f)
        assertEquals(32f, reference.activeSp, 0f)
        assertTrue(wide.activeSp in 32f..34f)
        assertEquals(44f, lyricTypographyForWidth(PlayerLayoutMode.WIDE_SINGLE, 600f).activeSp, 0f)
    }
}
