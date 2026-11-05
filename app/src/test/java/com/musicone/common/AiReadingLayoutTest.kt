package com.musicone

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class AiReadingLayoutTest {
    /** 改前的阅读列是 `widthIn(max = 800dp)` 再内缩 16dp，正文宽度必须是 min(宽, 800) - 32。 */
    private fun legacyContentWidth(width: Float) = minOf(width, 800f) - 32f

    @Test fun phoneKeepsTheOriginalSideMargin() {
        assertEquals(16f, aiChatReadingInset(393.dp).value, 0.001f)
        assertEquals(legacyContentWidth(393f), aiChatReadingContentWidth(393.dp).value, 0.001f)
    }

    @Test fun exactReadingWidthStillKeepsBothMargins() {
        assertEquals(16f, aiChatReadingInset(800.dp).value, 0.001f)
        assertEquals(768f, aiChatReadingContentWidth(800.dp).value, 0.001f)
    }

    @Test fun tabletCentersTheOriginalReadingColumn() {
        assertEquals(216f, aiChatReadingInset(1200.dp).value, 0.001f)
        assertEquals(768f, aiChatReadingContentWidth(1200.dp).value, 0.001f)
        assertEquals(1200f, aiChatReadingInset(1200.dp).value * 2 + aiChatReadingContentWidth(1200.dp).value, 0.001f)
    }

    @Test fun everyWidthMatchesTheLegacyColumn() {
        listOf(320f, 360f, 393f, 600f, 800f, 801f, 900f, 1024f, 1200f, 1600f).forEach { width ->
            assertEquals(width.toString(), legacyContentWidth(width), aiChatReadingContentWidth(width.dp).value, 0.001f)
        }
    }
}
