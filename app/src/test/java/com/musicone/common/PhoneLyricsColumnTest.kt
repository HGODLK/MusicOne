package com.musicone

import androidx.compose.ui.unit.Density
import org.junit.Assert.*
import org.junit.Test

class PhoneLyricsColumnTest {
    @Test fun portraitAndBothLandscapeSlotsLeaveExactlyTheSameTextWidth() {
        for (scale in listOf(1f, 2.625f, 3f, 3.5f)) {
            val density = Density(scale)
            val window = (393f * scale).toInt()
            val column = phoneLyricsColumnWidth(window, density)
            val portrait = window - with(density) { CompactPlayerLayoutSpec.horizontalPadding.roundToPx() } * 2
            assertEquals(portrait, column)
            for (touchPadding in listOf(47.2f * scale, 61.7f * scale)) {
                val slot = phoneLandscapeLyricsWidth(column, touchPadding)
                val start = kotlin.math.round(touchPadding).toInt()
                assertEquals(portrait, slot - start)
                val portraitType = lyricTypographyForWidth(PlayerLayoutMode.COMPACT_SINGLE, portrait / scale)
                val landscapeType = lyricTypographyForWidth(PlayerLayoutMode.COMPACT_SINGLE, column / scale)
                assertEquals(portraitType, landscapeType)
            }
        }
    }

    @Test fun narrowWindowKeepsAValidColumnWithoutChangingWithTheNavigationStrip() {
        assertEquals(1, phoneLyricsColumnWidth(20, Density(3f)))
        assertEquals(341, phoneLyricsColumnWidth(393, Density(1f)))
        assertEquals(401, phoneLandscapeLyricsWidth(341, 60f))
    }
}
