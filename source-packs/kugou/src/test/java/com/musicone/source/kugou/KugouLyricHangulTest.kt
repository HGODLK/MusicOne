package com.musicone.source.kugou

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouLyricHangulTest {
    @Test fun 按发音规则生成每音节读音() {
        val cases = mapOf(
            "봄날" to "bom nal",
            "아침은" to "a chi meun",
            "웃었지만" to "u seot ji man",
            "국물" to "gung mul",
            "같이" to "ga chi",
            "좋아" to "jo a",
            "좋다" to "jo ta",
            "축하" to "chu ka",
            "신라" to "sil la",
            "많이" to "ma ni",
            "읽어" to "il geo",
            "값이" to "gap si",
            "닫히다" to "da chi da",
            "없어" to "eop seo",
            "있어" to "i sseo",
            "없는" to "eom neun",
            "또" to "tto",
            "꽃" to "kkot",
        )
        cases.forEach { (text, reading) -> assertEquals(text, reading, hangulReadings(text).joinToString(" ")) }
    }

    @Test fun 单独的네按歌里的读音标注() {
        assertEquals(listOf("ni"), hangulReadings("네 "))
        assertEquals(listOf("ni", "ga"), hangulReadings("네가"))
        assertEquals(listOf("ne", "ge"), hangulReadings("네게"))
        assertEquals(listOf("doe", "ne", "yo"), hangulReadings("되네요"))
    }

    @Test fun 空白和其他字符断开连音() {
        assertEquals(listOf("guk", "eo"), hangulReadings("국 어"))
        assertEquals(listOf("guk", "eo"), hangulReadings("국(어"))
    }

    @Test fun 按词拆分且逐音节的词之间仍然连音() {
        assertEquals("a\tchi\tmeun", lyricHangulTransliteration(listOf("아", "침", "은 ")))
        assertEquals("nal\tbo go\tu seot ji man", lyricHangulTransliteration(listOf("날 ", "보고 ", "웃었지만")))
    }

    @Test fun 英文词留空且没有韩文或是署名行时不生成() {
        assertEquals("\t\t\tmal\thae", lyricHangulTransliteration(listOf("say ", "it ", "back ", "말", "해")))
        assertNull(lyricHangulTransliteration(listOf("say ", "it")))
        assertNull(lyricHangulTransliteration(listOf("作词：", "아이유")))
        assertNull(lyricHangulTransliteration(emptyList()))
    }

    @Test fun 韩语判断不被署名和少量假名干扰() {
        assertTrue(lyricHangulLanguageAllowed(listOf("작사：夜", "사랑해요", "あ")))
        assertFalse(lyricHangulLanguageAllowed(listOf("夜に駆ける", "사")))
        assertFalse(lyricHangulLanguageAllowed(listOf("乡间的小路", "사")))
        assertFalse(lyricHangulLanguageAllowed(listOf("only english")))
    }

    @Test fun 解析逐字歌词时韩语歌由韩文生成注音() {
        val lines = parseKugouKrc("[1000,600]<0,200,0>아<200,200,0>침<400,200,0>은 \n[2000,300]<0,300,0>say")
        assertEquals("a\tchi\tmeun", lines[0].transliteration)
        assertNull(lines[1].transliteration)
    }

    @Test fun 日语歌不走韩文转换() {
        val lines = parseKugouKrc("[1000,400]<0,200,0>こ<200,200,0>こ")
        assertNull(lines[0].transliteration)
    }
}
