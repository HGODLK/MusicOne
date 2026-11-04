package com.musicone

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test

class LyricReadingPositionTest {
    @Test fun wrappedParagraphKeepsItsReadingFractionAndSeparatePlaybackFocus() {
        val position = LyricReadingPosition(7, -200f, 300, following = false,
            rowHeight = 800, playbackLine = 12)
        assertEquals(-75, position.targetOffset(900, 300))
        assertEquals(7, position.line)
        assertEquals(12, position.playbackLine)
        assertEquals(-200, position.copy(offset = -75f, rowHeight = 300).targetOffset(300, 800))
        assertEquals(0, position.copy(following = true).targetOffset(900, 300))
    }

    @Test fun playbackFocusUsesThePreparedTargetAnchorInsteadOfAnOldViewportOffset() {
        val position = LyricReadingPosition(12, -80.5f, 900, following = true)
        for (height in listOf(320, 900, 1400)) assertEquals(0, position.targetOffset(height))
        assertEquals(12, position.line)
    }

    @Test fun manualReadingRetainsItsRowAndRelativeOffsetAcrossBothAspectRatios() {
        val position = LyricReadingPosition(7, -75f, 300, following = false)
        assertEquals(-225, position.targetOffset(900))
        assertEquals(-75, position.copy(offset = -225f, height = 900).targetOffset(300))
        assertEquals(7, position.line)
    }

    @Test fun wholeWindowBlendStartsWithTheExactSourceAndFollowsBothRotationSprings() {
        for (rotation in listOf(90f, -90f)) {
            val source = PhoneTurnPose(Offset(80f, 600f), Size(380f, 1000f), 0f)
            val target = PhoneTurnPose(Offset(700f, 250f), Size(650f, 380f), rotation)
            assertEquals(0f, phoneLyricsTurnBlend(source, source.copy(size = target.size), target), 0f)
            val middle = target.copy(pivot = (source.pivot + target.pivot) / 2f, rotation = rotation / 2f)
            assertEquals(.5f, phoneLyricsTurnBlend(source, middle, target), .001f)
            assertEquals(1f, phoneLyricsTurnBlend(source, target, target), 0f)
        }
    }
}
