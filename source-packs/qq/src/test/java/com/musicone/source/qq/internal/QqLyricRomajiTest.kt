package com.musicone.source.qq.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QqLyricRomajiTest {
    private val original = """<Lyric_1 LyricContent="[1000,1000]心(1000,600)の(1600,400)&#10;[3000,500]あ(3000,500)"/>"""

    @Test fun moraTimesAreAssignedToWordsAndKeptRelative() {
        val raw = """<Lyric_1 LyricContent="[1000,1000]ko (1000,200)ko (1200,200)ro (1400,200)no (1600,400)&#10;[3001,500]a (3001,500)"/>"""
        val lines = parseQqQrc(original).withQqRomaji(raw)
        assertEquals("ko@0+200 ko@200+200 ro@400+200\tno@0+400", lines[0].transliteration)
        assertEquals("a@1+500", lines[1].transliteration)
    }

    @Test fun wrongKanaOrMissingRomajiLeavesLineWithoutRuby() {
        val lines = parseQqQrc(original).withQqRomaji("""<Lyric_1 LyricContent="[1000,1000]ko (1000,600)ka (1600,400)"/>""")
        assertNull(lines[0].transliteration)
        assertNull(lines[1].transliteration)
        assertNull(parseQqQrc(original).withQqRomaji("").first().transliteration)
    }

    @Test fun chineseSongIsLeftAlone() {
        val chinese = parseQqQrc("""<Lyric_1 LyricContent="[1000,1000]乡(1000,500)间(1500,500)"/>""")
        assertNull(chinese.withQqRomaji("""<Lyric_1 LyricContent="[1000,1000]xiang (1000,500)jian (1500,500)"/>""").single().transliteration)
    }

    @Test fun parserVersionMatchesProviderVersion() {
        assertEquals(CURRENT_LYRIC_PARSER_VERSION, parseQqQrc(original).first().parserVersion)
    }
}
