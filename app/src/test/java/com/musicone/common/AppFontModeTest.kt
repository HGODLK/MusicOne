package com.musicone

import androidx.compose.material3.Typography
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.*
import org.junit.Test

class AppFontModeTest {
    @Test fun missingOrUnknownPreferenceKeepsTheExistingDefault() {
        assertEquals(AppFontMode.DEFAULT, AppFontMode.fromStored(null))
        assertEquals(AppFontMode.DEFAULT, AppFontMode.fromStored("unknown"))
        AppFontMode.entries.forEach { assertEquals(it, AppFontMode.fromStored(it.storedValue)) }
    }

    @Test fun defaultLyricsKeepTheExistingSerifFamilies() {
        for ((text, script) in samples) {
            val lyric = musicOneDisplayAnnotatedString(text, hint = script, mode = AppFontMode.DEFAULT)
            assertEquals(text, lyric.text)
            assertTrue(lyric.spanStyles.all { it.item.fontFamily == musicOneDisplayFontFamily(script) })
        }
    }

    @Test fun sansSerifLyricsUseTheMatchingSarasaFamilyForEveryScript() {
        for ((text, script) in samples) {
            val lyric = musicOneDisplayAnnotatedString(text, hint = script, mode = AppFontMode.SANS_SERIF)
            val translation = musicOneUiAnnotatedString(text, hint = script, mode = AppFontMode.SANS_SERIF)
            assertEquals(text, lyric.text)
            assertEquals(translation, lyric)
            assertTrue(lyric.spanStyles.all { it.item.fontFamily == musicOneUiFontFamily(script) })
        }
        assertEquals(
            musicOneEditorialAnnotatedString("歌单 Hello", mode = AppFontMode.DEFAULT),
            musicOneEditorialAnnotatedString("歌单 Hello", mode = AppFontMode.SANS_SERIF),
        )
    }

    @Test fun systemModeRemovesBundledFontsFromUiLyricsEditorialAndSymbolText() {
        val text = "音乐 音樂 かな 한글 Hello 𝓐"
        assertEquals(AnnotatedString(text), musicOneUiAnnotatedString(text, mode = AppFontMode.SYSTEM))
        assertEquals(AnnotatedString(text), musicOneDisplayAnnotatedString(text, mode = AppFontMode.SYSTEM))
        assertEquals(AnnotatedString(text), musicOneEditorialAnnotatedString(text, mode = AppFontMode.SYSTEM))
        assertEquals(AnnotatedString(text), AnnotatedString(text).withMusicOneSymbolFallback(AppFontMode.SYSTEM))
        assertTrue(AnnotatedString(text).withMusicOneSymbolFallback(AppFontMode.DEFAULT).spanStyles.isNotEmpty())
    }

    @Test fun everyThemeRoleKeepsItsOriginalMetricsWhenFollowingSystem() {
        val original = styles(MusicOneTypography)
        val system = styles(musicOneTypography(AppFontMode.SYSTEM))
        original.zip(system).forEach { (before, after) ->
            assertEquals(before.copy(fontFamily = FontFamily.Default), after)
        }
        assertSame(MusicOneTypography, musicOneTypography(AppFontMode.DEFAULT))
        assertSame(MusicOneTypography, musicOneTypography(AppFontMode.SANS_SERIF))
    }

    @Test fun switchingFontKeepsLyricTextWordOffsetsAndWesternSizeCompensation() {
        val text = "你好 Hello café 世界"
        val original = lyricOriginalAnnotatedString(text, AppFontMode.DEFAULT)
        for (mode in AppFontMode.entries) {
            val shown = lyricOriginalAnnotatedString(text, mode)
            assertEquals(text, shown.text)
            assertEquals(original.spanStyles.filter { it.item.fontFamily == null },
                shown.spanStyles.filter { it.item.fontFamily == null })
            if (mode == AppFontMode.SYSTEM) assertTrue(shown.spanStyles.all { it.item.fontFamily == null })
        }
    }

    private val samples = listOf(
        "音乐" to MusicOneScript.SC,
        "音樂" to MusicOneScript.TC,
        "かな" to MusicOneScript.JA,
        "한글" to MusicOneScript.KO,
        "Hello" to MusicOneScript.LATIN,
    )

    private fun styles(typography: Typography): List<TextStyle> = with(typography) {
        listOf(displayLarge, displayMedium, displaySmall, headlineLarge, headlineMedium, headlineSmall,
            titleLarge, titleMedium, titleSmall, bodyLarge, bodyMedium, bodySmall, labelLarge, labelMedium, labelSmall)
    }
}
