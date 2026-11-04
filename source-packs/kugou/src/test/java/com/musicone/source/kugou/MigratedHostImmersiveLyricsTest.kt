package com.musicone.source.kugou

import org.junit.Assert.*
import org.junit.Test

class MigratedHostImmersiveLyricsTest {
    @Test fun kugouKrcParserReadsLineAndWordTiming() {
        val lines = parseKugouKrc("[1000,800]<0,400,0>你<400,400,0>好\n[2000,800]<0,800,0>世界")

        assertEquals(listOf(1_000L, 2_000L), lines.map { it.timeMs })
        assertEquals(listOf("你好", "世界"), lines.map { it.text })
    }

}
