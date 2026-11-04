package com.musicone

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class SourcePlayerWindowInsetsTest {
    private fun PaddingValues.edges() = listOf(
        calculateLeftPadding(LayoutDirection.Ltr), calculateTopPadding(),
        calculateRightPadding(LayoutDirection.Ltr), calculateBottomPadding(),
    )

    @Test fun portraitAndTabletKeepAllFourPhysicalEdges() {
        assertEquals(listOf(1.dp, 24.dp, 3.dp, 16.dp),
            phonePlayerWindowPadding(1.dp, 24.dp, 3.dp, 16.dp, 0).edges())
    }

    @Test fun clockwiseCanvasMovesTheTopCutoutToTheLeft() {
        assertEquals(listOf(24.dp, 3.dp, 16.dp, 1.dp),
            phonePlayerWindowPadding(1.dp, 24.dp, 3.dp, 16.dp, 90).edges())
    }

    @Test fun counterclockwiseCanvasMovesTheTopCutoutToTheRight() {
        assertEquals(listOf(16.dp, 1.dp, 24.dp, 3.dp),
            phonePlayerWindowPadding(1.dp, 24.dp, 3.dp, 16.dp, -90).edges())
    }

    @Test fun nativeBalancedLandscapeKeepsItsExistingSymmetricSides() {
        assertEquals(listOf(24.dp, 3.dp, 24.dp, 1.dp),
            phonePlayerWindowPadding(1.dp, 24.dp, 3.dp, 16.dp, 90, true).edges())
        assertEquals(listOf(24.dp, 1.dp, 24.dp, 3.dp),
            phonePlayerWindowPadding(1.dp, 24.dp, 3.dp, 16.dp, -90, true).edges())
    }
}
