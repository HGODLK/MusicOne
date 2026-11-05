package com.musicone.source.qq.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QqLyricHangulTest {
    private val original = """<Lyric_1 LyricContent="[1000,1000]날 (1000,200)웃었지만(1200,800)&#10;[3000,500]say (3000,500)"/>"""

    private fun lines(roma: String) = parseQqQrc(original).withQqRomaji("""<Lyric_1 LyricContent="$roma"/>""")

    @Test fun 词内音节平分时间且英文词留空() {
        val result = lines("[1000,1000]nal  (1000,200)u seot ji man (1200,800)&#10;[3000,500]say (3000,500)")
        assertEquals("nal@0+200\tu@0+200 seot@200+200 ji@400+200 man@600+200", result[0].transliteration)
        assertNull(result[1].transliteration)
    }

    @Test fun 紧音统一成标准写法() {
        val words = listOf(LyricWord(0, 300, "꽃또"), LyricWord(300, 400, "뿐"))
        val romaji = listOf(LyricWord(0, 300, "ggot ddo "), LyricWord(300, 400, "bbun "))
        assertEquals("kkot@0+150 tto@150+150\tppun@0+100", qqHangulTransliteration(words, romaji))
    }

    @Test fun 对不上的句子不显示() {
        val words = listOf(LyricWord(0, 300, "봄날"))
        // 音节数不符
        assertNull(qqHangulTransliteration(words, listOf(LyricWord(0, 300, "bom "))))
        // 元音对不上说明错位
        assertNull(qqHangulTransliteration(words, listOf(LyricWord(0, 300, "bom nil "))))
        // 词数不符，例如开头的 AI 声明行
        assertNull(qqHangulTransliteration(words, listOf(LyricWord(0, 100, "以"), LyricWord(100, 200, "下"))))
        // 署名行
        assertNull(qqHangulTransliteration(listOf(LyricWord(0, 300, "작사：")), listOf(LyricWord(0, 300, "jak sa "))))
    }

    @Test fun 네唱成ni可以通过校验() {
        assertEquals("ni@0+100", qqHangulTransliteration(listOf(LyricWord(0, 100, "네 ")), listOf(LyricWord(0, 100, "ni "))))
    }

    @Test fun 韩语判断先于日语() {
        assertTrue(lyricHangulLanguageAllowed(listOf("사랑해요", "あ")))
        assertFalse(lyricHangulLanguageAllowed(listOf("夜に駆ける", "사")))
    }

    @Test fun 日语歌仍走原来的校验() {
        val japanese = """<Lyric_1 LyricContent="[1000,1000]心(1000,600)の(1600,400)"/>"""
        val raw = """<Lyric_1 LyricContent="[1000,1000]ko (1000,200)ko (1200,200)ro (1400,200)no (1600,400)"/>"""
        assertEquals("ko@0+200 ko@200+200 ro@400+200\tno@0+400",
            parseQqQrc(japanese).withQqRomaji(raw)[0].transliteration)
    }
}
