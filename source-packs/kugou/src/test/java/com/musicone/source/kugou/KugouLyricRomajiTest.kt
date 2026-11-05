package com.musicone.source.kugou

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouLyricRomajiTest {
    private fun convert(words: List<String>, romaji: List<String>) =
        lyricRomajiTransliteration(words, words.indices.map { it * 100L }, romaji.map(::romajiMoras))

    @Test fun wordAlignedRomajiPassesThrough() = assertEquals("fu ta\tri\tda\tke\tno", convert(
        listOf("二", "人", "だ", "け", "の"), listOf("fu ta ", "ri ", "da ", "ke ", "no ")))

    @Test fun shiftedRowsAreRepairedByKanaAnchors() {
        // 酷狗把 フェ 拆到两个词时后面的读音会整体错位。
        assertEquals("fe\t+\tn\tsu\t", convert(listOf("フ", "ェ", "ン", "ス", " "), listOf("fe ", "n ", "su ", " ", "")))
        // 幸せ 的 せ 被分到了 wa se。
        assertEquals("shi a wa\tse", convert(listOf("幸", "せ"), listOf("shi a ", "wa se ")))
    }

    @Test fun sokuonAndSmallKanaJoinTheirNeighbour() {
        assertEquals("ka\ttte\t+", convert(listOf("か", "っ", "て"), listOf("ka ", "tte ", "")))
        assertEquals("ka\tt te\t+", convert(listOf("か", "っ", "て"), listOf("ka ", "t te ", "")))
        assertEquals("ja\t+", convert(listOf("じ", "ゃ"), listOf("ja ", "")))
    }

    @Test fun adjacentHanShareReadingWhenSourceGivesItOnce() {
        assertEquals("kyou\t+\twa", convert(listOf("今", "日", "は"), listOf("kyou ", "", "wa ")))
        // 摊平后相邻汉字无法细分，只能合标。
        assertEquals("ki mi\t+\tno", convert(listOf("君", "達", "の"), listOf("", "ki mi ", "no ")))
    }

    @Test fun particlesAndLatinAreHandled() {
        assertEquals("wa\t\tno", convert(listOf("は", "Lemon", "の"), listOf("wa ", "Lemon ", "no ")))
        assertNull(convert(listOf("YOASOBI"), listOf("YOASOBI ")))
    }

    @Test fun wrongReadingAndCreditLinesShowNothing() {
        assertNull(convert(listOf("あ", "い"), listOf("ka ", "ki ")))
        assertNull(convert(listOf("词", "：", "米"), listOf("shi ", ": ", "yo ne ")))
    }

    @Test fun onlyJapaneseSongsGetRomaji() {
        assertTrue(lyricRomajiLanguageAllowed(listOf("二人だけの空が広がる夜に")))
        assertFalse(lyricRomajiLanguageAllowed(listOf("乡间的歌谣永远的依靠", "永远的依靠の")))
        assertFalse(lyricRomajiLanguageAllowed(listOf("Hello world")))
    }
}
