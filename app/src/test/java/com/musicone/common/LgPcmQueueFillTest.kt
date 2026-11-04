package com.musicone

import androidx.media3.common.C
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class LgPcmQueueFillTest {
    @Test fun oneCallFillsAvailableCapacityInsteadOfOnlyOneBlock() {
        val input = ByteBuffer.allocate(48000 * 2 * 2)
        val scratch = ByteBuffer.allocate(960 * 2 * 3)
        var writes = 0
        var frames = 0L
        val result = fillLgPcmQueue(input, C.ENCODING_PCM_16BIT, 24, 2, scratch,
            enqueue = { if (writes == 10) 0 else { ++writes; it.remaining() } }, submitted = { frames += it })
        assertEquals(0, result)
        assertEquals(10, writes)
        assertEquals(9600L, frames)
        assertEquals(9600 * 2 * 2, input.position())
    }

    @Test fun partialAcceptanceThenRetryPreservesEverySample() {
        val input = ByteBuffer.wrap(ByteArray(24) { it.toByte() })
        val scratch = ByteBuffer.allocate(36)
        val expected = ByteBuffer.allocate(36)
        packLgPcm(input, C.ENCODING_PCM_16BIT, 24, expected)
        val received = ArrayList<Byte>()
        var calls = 0
        var frames = 0L
        assertEquals(0, fillLgPcmQueue(input, C.ENCODING_PCM_16BIT, 24, 2, scratch, enqueue = {
            if (++calls == 1) { repeat(12) { _ -> received.add(it.get()) }; 12 } else 0
        }, submitted = { frames += it }))
        assertEquals(8, input.position())
        assertEquals(1, fillLgPcmQueue(input, C.ENCODING_PCM_16BIT, 24, 2, scratch, enqueue = {
            val bytes = it.remaining()
            while (it.hasRemaining()) received.add(it.get())
            bytes
        }, submitted = { frames += it }))
        assertEquals(6L, frames)
        assertArrayEquals(expected.array(), received.toByteArray())
    }

    @Test fun failureLeavesUnacceptedInputForFallback() {
        val input = ByteBuffer.allocate(16)
        val scratch = ByteBuffer.allocate(12)
        var calls = 0
        var frames = 0L
        assertEquals(-1, fillLgPcmQueue(input, C.ENCODING_PCM_16BIT, 24, 2, scratch,
            enqueue = { if (++calls == 1) it.remaining() else -1 }, submitted = { frames += it }))
        assertEquals(8, input.position())
        assertEquals(2L, frames)
    }

    @Test fun shortFinalBufferIsAcceptedWithoutPaddingOrDroppingSamples() {
        val input = ByteBuffer.wrap(byteArrayOf(1, 0, 2, 0))
        val scratch = ByteBuffer.allocate(960 * 6)
        var frames = 0L
        assertEquals(1, fillLgPcmQueue(input, C.ENCODING_PCM_16BIT, 24, 2, scratch,
            enqueue = { assertEquals(6, it.remaining()); it.remaining() }, submitted = { frames += it }))
        assertEquals(1L, frames)
        assertFalse(input.hasRemaining())
    }
}
