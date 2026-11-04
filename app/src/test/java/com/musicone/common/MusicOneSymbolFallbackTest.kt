package com.musicone

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.*
import org.junit.Test

class MusicOneSymbolFallbackTest {
    @Test fun ordinaryTextAndEmojiKeepTheirExistingStyles() {
        val text = AnnotatedString("音乐 Hello 🎵", SpanStyle(color = Color.Red))
        assertSame(text, text.withMusicOneSymbolFallback())
    }

    @Test fun mathematicalLettersKeepUtf16OffsetsAndExistingAnnotations() {
        val text = "中𝓐文🎵𝟠"
        val original = AnnotatedString.Builder(text).apply {
            addStyle(SpanStyle(color = Color.Red), 0, text.length)
            addStringAnnotation("track", "identity", 0, text.length)
        }.toAnnotatedString()
        val shown = original.withMusicOneSymbolFallback()
        assertEquals(text, shown.text)
        assertEquals(original.getStringAnnotations(0, text.length), shown.getStringAnnotations(0, text.length))
        assertEquals(original.spanStyles.first(), shown.spanStyles.first())
        val fallback = shown.spanStyles.drop(1)
        assertEquals(listOf(1 to 3, 6 to 8), fallback.map { it.start to it.end })
        assertTrue(fallback.all { it.item.fontFamily != null &&
            it.item.fontWeight == FontWeight.Normal && it.item.fontStyle == FontStyle.Normal })
    }

    @Test fun sharedTextFormattersIncludeFallbackWithoutChangingText() {
        for (formatter in listOf<(String) -> AnnotatedString>(
            { musicOneUiAnnotatedString(it) }, { musicOneDisplayAnnotatedString(it) },
            { musicOneEditorialAnnotatedString(it) },
        )) {
            val shown = formatter("𝓐 音乐")
            assertEquals("𝓐 音乐", shown.text)
            assertTrue(shown.spanStyles.any { it.start == 0 && it.end == 2 &&
                it.item.fontStyle == FontStyle.Normal && it.item.fontWeight == FontWeight.Normal })
        }
    }
}
