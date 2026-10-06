package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class QqFlacHeaderTest {
    private fun header(rate: Long, samples: Long): ByteArray = ByteArray(42).apply {
        "fLaC".toByteArray().copyInto(this)
        this[4] = 0x80.toByte()
        this[7] = 34
        val packed = (rate shl 44) or (1L shl 41) or (23L shl 36) or samples
        for (index in 18..25) this[index] = (packed ushr ((25 - index) * 8)).toByte()
    }

    @Test fun fullFlacDurationDoesNotDependOnSystemMime() {
        val duration = qqFlacDuration(header(44_100, 44_100L * 332 + 8_200))!!
        assertEquals(332_185L, duration)
        assertTrue(qqMediaDurationMatches(duration, 332_000))
        assertFalse(qqMediaDurationMatches(qqFlacDuration(header(48_000, 48_000L * 60))!!, 332_000))
    }

    @Test fun rejectsOtherFormatsTruncatedAndUnknownLength() {
        assertNull(qqFlacDuration(ByteArray(42)))
        assertNull(qqFlacDuration(header(48_000, 48_000).copyOf(30)))
        assertNull(qqFlacDuration(header(0, 48_000)))
        assertNull(qqFlacDuration(header(48_000, 0)))
    }
}
