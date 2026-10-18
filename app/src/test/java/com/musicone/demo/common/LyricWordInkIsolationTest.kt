package com.musicone.demo

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import org.junit.Assert.*
import org.junit.Test

class LyricWordInkIsolationTest {
    @Test fun isolatedHookDoesNotKeepNeighbourLettersVisible() {
        val source = AnnotatedString("AJj")
        val masked = lyricInkMaskedText(source, listOf(TextRange(1, 2)))
        assertEquals(source.text, masked.text)
        assertTrue(hidden(masked, 0))
        assertFalse(hidden(masked, 1))
        assertTrue(hidden(masked, 2))
    }

    @Test fun maskingPreservesMixedFontsParagraphsAndSurrogatePairs() {
        val source = buildAnnotatedString {
            append("A😀j\nJ中")
            addStyle(SpanStyle(fontFamily = FontFamily.Serif, fontSize = 44.sp), 0, 6)
            addStyle(SpanStyle(fontFamily = FontFamily.SansSerif), 6, 7)
            addStyle(ParagraphStyle(lineHeight = 56.sp), 0, 7)
            addStringAnnotation("identity", "original", 0, 7)
        }
        val masked = lyricInkMaskedText(source, listOf(TextRange(1, 4)))
        assertEquals(source.text, masked.text)
        assertEquals(source.paragraphStyles, masked.paragraphStyles)
        assertEquals(source.spanStyles, masked.spanStyles.take(source.spanStyles.size))
        assertEquals(source.getStringAnnotations(0, 7), masked.getStringAnnotations(0, 7))
        assertFalse(hidden(masked, 1))
        assertFalse(hidden(masked, 2))
        assertFalse(hidden(masked, 3))
        assertTrue(hidden(masked, 5))
        assertTrue(hidden(masked, 6))
    }

    @Test fun wrappedPartsKeepOnlyTheirOwnLineVisible() {
        val source = AnnotatedString("j中\nJ文")
        val first = lyricInkMaskedText(source, listOf(TextRange(0, 2)))
        val second = lyricInkMaskedText(source, listOf(TextRange(3, 5)))
        assertFalse(hidden(first, 0))
        assertTrue(hidden(first, 3))
        assertTrue(hidden(second, 0))
        assertFalse(hidden(second, 3))
    }

    @Test fun fullyOwnedTextLeavesNoDuplicateBaseInk() {
        assertEquals(emptyList<TextRange>(), lyricInkUncoveredRanges(3,
            listOf(TextRange(0, 1), TextRange(1, 2), TextRange(2, 3))))
        assertEquals(listOf(TextRange(0, 1), TextRange(2, 3)),
            lyricInkUncoveredRanges(3, listOf(TextRange(1, 2))))
    }

    @Test fun ordinaryLyricsKeepTheSharedRasterPath() {
        assertFalse(lyricWordInkIsolationRequired("你好 Hello"))
        assertTrue(lyricWordInkIsolationRequired("Just"))
        assertTrue(lyricWordInkIsolationRequired("aj"))
    }

    private fun hidden(text: AnnotatedString, offset: Int): Boolean = text.spanStyles.any {
        offset in it.start until it.end && it.item.color == Color.Transparent
    }
}
