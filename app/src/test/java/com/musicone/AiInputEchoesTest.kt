package com.musicone

import org.junit.Assert.*
import org.junit.Test

class AiInputEchoesTest {
    @Test fun delayedIntermediateEchoDoesNotReplaceNewerLocalComposition() {
        val echoes = AiInputEchoes()
        echoes.sent("多"); echoes.sent("多少")
        assertTrue(echoes.consume("多"))
        assertTrue(echoes.consume("多少"))
        assertFalse(echoes.consume("发消息"))
    }

    @Test fun conflatedRepeatedTextAcknowledgesTheLatestMatchingEdit() {
        val echoes = AiInputEchoes()
        echoes.sent("a"); echoes.sent("ab"); echoes.sent("a")
        assertTrue(echoes.consume("a"))
        assertFalse(echoes.consume("ab"))
    }

    @Test fun externalClearDiscardsOldEchoesAndAllowsARealReplacement() {
        val echoes = AiInputEchoes()
        echoes.sent("多少")
        assertFalse(echoes.consume(""))
        assertFalse(echoes.consume("多少"))
        echoes.sent("新的输入")
        assertTrue(echoes.consume("新的输入"))
    }
}
