package com.musicone.source.qq.internal

import org.junit.Assert.*
import org.junit.Test

class MigratedHostQrcTextTest {
    @Test fun qrcPreservesParenthesesQuotesAndUntimedTrailingText() {
        val result = parseQqQrc("<Lyric_1 LyricContent=\"[0,1000](A Sky)(0,400)\"光\"(400,500)尾&#10;[22305,1000]后续(22305,1000)\"/>")
        assertEquals(2, result.size)
        assertEquals("(A Sky)\"光\"尾", result[0].text)
        assertEquals(listOf("(A Sky)", "\"光\""), result[0].words.map { it.text })
        assertEquals(listOf(0L, 22_305L), result.map { it.timeMs })
        assertTrue(result.all { it.parserVersion == CURRENT_LYRIC_PARSER_VERSION })
    }
}
