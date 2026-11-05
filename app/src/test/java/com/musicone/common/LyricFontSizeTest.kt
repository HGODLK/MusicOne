package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricFontSizeTest {
    @Test
    fun mediumKeepsCurrentTypography() {
        val type = lyricTypographyForWidth(PlayerLayoutMode.COMPACT_SINGLE, 340f, LyricFontSize.MEDIUM)
        assertEquals(32f, type.activeSp, .001f)
        assertEquals(42f, type.activeLineSp, .001f)
        assertEquals(13f, type.translationSp, .001f)
        assertEquals(19f, type.translationLineSp, .001f)
    }

    @Test
    fun scalesMainTextFullyAndSecondaryTextByHalf() {
        val small = lyricTypographyForWidth(PlayerLayoutMode.COMPACT_SINGLE, 340f, LyricFontSize.EXTRA_SMALL)
        assertEquals(22.4f, small.activeSp, .001f)
        assertEquals(29.4f, small.activeLineSp, .001f)
        assertEquals(11.05f, small.translationSp, .001f)
        val large = lyricTypographyForWidth(PlayerLayoutMode.WIDE_SINGLE, 600f, LyricFontSize.LARGE)
        assertEquals(49.5f, large.activeSp, .001f)
    }

    @Test
    fun unknownStoredValueFallsBackToMedium() {
        assertEquals(LyricFontSize.MEDIUM, LyricFontSize.fromStored(null))
        assertEquals(LyricFontSize.MEDIUM, LyricFontSize.fromStored("huge"))
        assertEquals(LyricFontSize.SMALL, LyricFontSize.fromStored("small"))
    }
}
