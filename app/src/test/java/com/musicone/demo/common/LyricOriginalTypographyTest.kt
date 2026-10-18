package com.musicone.demo

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import org.junit.Assert.*
import org.junit.Test

class LyricOriginalTypographyTest {
    @Test fun westernScriptsAndAccentsShrinkWithoutChangingTextOrFontSelection() {
        for (text in listOf("Keep the rhythm moving", "café, Straße, corazón", "Đường về nhà",
            "Ελληνικά", "Привет мир", "Cafe\u0301", "12345")) {
            val original = musicOneDisplayAnnotatedString(text)
            val shown = lyricOriginalAnnotatedString(text)
            assertEquals(text, shown.text)
            assertEquals(original.spanStyles, shown.spanStyles.filter { it.item.fontSize == TextUnit.Unspecified })
            assertTrue(shown.spanStyles.any { it.item.fontSize == .82.em && it.start == 0 && it.end == text.length })
        }
    }

    @Test fun mixedLyricsKeepCjkAtTheirOriginalSizeAndPreserveWordOffsets() {
        val text = "你好 Hello café 世界 Ελληνικά Привет"
        val shown = lyricOriginalAnnotatedString(text)
        assertEquals(text, shown.text)
        val scaled = shown.spanStyles.filter { it.item.fontSize == .82.em }
        for (word in listOf("Hello", "café", "Ελληνικά", "Привет")) {
            val start = text.indexOf(word)
            assertTrue(scaled.any { it.start <= start && it.end >= start + word.length })
        }
        text.forEachIndexed { index, char ->
            if (char in "你好世界") assertFalse(scaled.any { index in it.start until it.end })
        }
    }

    @Test fun cjkLyricsAndTheSharedTranslationFormatterKeepTheirOriginalStyles() {
        for (text in listOf("音乐让每一刻自然流动", "夢のつづき", "음악을 들어요")) {
            assertEquals(musicOneDisplayAnnotatedString(text), lyricOriginalAnnotatedString(text))
        }
        assertTrue(musicOneUiAnnotatedString("English translation").spanStyles
            .all { it.item.fontSize == TextUnit.Unspecified })
    }
}
