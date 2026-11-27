package com.musicone

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AutoMixPcmTest {
    @Test
    fun pcmRoundTripKeepsSamplesForEveryEncoding() {
        val frames = 64
        val src = FloatArray(frames * 2) { i -> ((i % 37) - 18) / 20f }
        for (encoding in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)) {
            val format = AutoMixPcmFormat(48_000, 2, encoding)
            val buffer = ByteBuffer.allocate(frames * format.bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)
            writePcmFrames(src, 0, frames, format, buffer)
            buffer.flip()
            val back = FloatArray(frames * 2)
            readPcmFrames(buffer, format, frames, back)
            assertEquals("读取不应移动缓冲位置", 0, buffer.position())
            val tolerance = if (encoding == C.ENCODING_PCM_16BIT) 1e-4f else 1e-6f
            for (i in src.indices) assertEquals(src[i], back[i], tolerance)
        }
    }

    @Test
    fun monoIsDuplicatedOnReadAndAveragedOnWrite() {
        val format = AutoMixPcmFormat(44_100, 1, C.ENCODING_PCM_FLOAT)
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        writePcmFrames(floatArrayOf(0.2f, 0.4f, -0.5f, 0.1f), 0, 2, format, buffer)
        buffer.flip()
        val back = FloatArray(4)
        readPcmFrames(buffer, format, 2, back)
        assertEquals(0.3f, back[0], 1e-6f)
        assertEquals(0.3f, back[1], 1e-6f)
        assertEquals(-0.2f, back[2], 1e-6f)
    }

    @Test
    fun frameQueueAddressesAbsoluteFrames() {
        val queue = AutoMixFrameQueue(2)
        queue.clear(100L)
        queue.append(FloatArray(10) { it.toFloat() }, 5)
        assertTrue(queue.has(104L))
        assertFalse(queue.has(105L))
        queue.dropBefore(102L)
        assertEquals(102L, queue.start)
        assertEquals(4f, queue.left(102L))
        assertEquals(9f, queue.right(104L))
    }
}
