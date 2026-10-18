package com.musicone.nativeaudio

import androidx.media3.common.C
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class NativePcmResamplerTest {
    private fun little(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun converted(input: ByteArray, encoding: Int, inputRate: Int, outputRate: Int, chunk: Int,
        gain: Double = 1.0): ByteArray {
        val frameBytes = (if (encoding == C.ENCODING_PCM_24BIT) 3 else if (encoding == C.ENCODING_PCM_16BIT) 2 else 4) * 2
        val result = ByteArrayOutputStream()
        NativePcmResampler(inputRate, outputRate, 2, encoding).use { converter ->
            var offset = 0
            while (offset < input.size) {
                val end = minOf(input.size, offset + chunk * frameBytes)
                result.write(converter.convert(input.copyOfRange(offset, end), gain))
                offset = end
            }
            while (true) {
                val tail = converter.drain(gain)
                if (tail.isEmpty()) break
                result.write(tail)
            }
        }
        return result.toByteArray()
    }

    @Test fun sameRate32bitDoesNotLoseTheLowestEightBits() {
        val values = intArrayOf(1, -1, 0x12345679, -0x12345679, Int.MAX_VALUE, Int.MIN_VALUE)
        val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach(bytes::putInt)
        assertArrayEquals(bytes.array(), converted(bytes.array(), C.ENCODING_PCM_32BIT, 192000, 192000, 1))
    }

    @Test fun packed24AndFloatInputsPreserveChannelOrderAndAmplitude() {
        val pcm24 = byteArrayOf(0x56, 0x34, 0x12, 0xaa.toByte(), 0xcb.toByte(), 0xed.toByte())
        val integers = little(converted(pcm24, C.ENCODING_PCM_24BIT, 192000, 192000, 1))
        assertEquals(0x12345600, integers.int)
        assertEquals(-0x12345600, integers.int)
        val floats = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(.25f).putFloat(-.5f).array()
        val output = little(converted(floats, C.ENCODING_PCM_FLOAT, 192000, 192000, 1))
        assertEquals(0x20000000, output.int)
        assertEquals(-0x40000000, output.int)
    }

    @Test fun unsupportedRateConversionKeepsPitchDurationAndTailAcrossChunkBoundaries() {
        for (rate in listOf(88200, 176400, 352800)) {
            val frames = rate / 2
            val input = ByteBuffer.allocate(frames * 8).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames) { frame ->
                val sample = (sin(2 * PI * 1000 * frame / rate) * 0x30000000).roundToInt()
                input.putInt(sample).putInt(-sample)
            }
            val one = converted(input.array(), C.ENCODING_PCM_32BIT, rate, 192000, frames)
            val chunks = converted(input.array(), C.ENCODING_PCM_32BIT, rate, 192000, 1152)
            assertArrayEquals("分块不能重置滤波历史或丢失尾音", one, chunks)
            assertTrue(abs(one.size / 8 - 96000) <= 1)
            val output = little(one)
            for (frame in 1000 until 95000 step 23) {
                val expected = sin(2 * PI * 1000 * frame / 192000) * 0x30000000
                val left = output.getInt(frame * 8)
                val right = output.getInt(frame * 8 + 4)
                assertTrue("转换不能变调", abs(left - expected) < 50000)
                assertTrue("声道不能串位", abs(left.toLong() + right) <= 2)
            }
        }
    }

    @Test fun resetDiscardsOldFilterHistoryAndCanRestartAfterDrain() {
        val input = ByteBuffer.allocate(2048 * 8).order(ByteOrder.LITTLE_ENDIAN)
        repeat(4096) { input.putInt(if (it % 2 == 0) 0x12345679 else -0x12345679) }
        NativePcmResampler(176400, 192000, 2, C.ENCODING_PCM_32BIT).use { converter ->
            val first = converter.convert(input.array())
            converter.drain()
            converter.reset()
            assertArrayEquals(first, converter.convert(input.array()))
        }
    }

    @Test fun playbackVolumeIsAppliedBeforeFinalIntegerClipping() {
        val input = ByteBuffer.allocate(8192 * 8).order(ByteOrder.LITTLE_ENDIAN)
        val half = ByteBuffer.allocate(input.capacity()).order(ByteOrder.LITTLE_ENDIAN)
        repeat(8192) { frame ->
            val value = if (frame % 88 < 44) 0x70000000 else -0x70000000
            repeat(2) { input.putInt(value); half.putInt(value / 2) }
        }
        val after = little(converted(input.array(), C.ENCODING_PCM_32BIT, 176400, 192000, 1152, .5))
        val before = little(converted(half.array(), C.ENCODING_PCM_32BIT, 176400, 192000, 1152))
        assertEquals(before.remaining(), after.remaining())
        while (after.hasRemaining()) assertTrue(abs(after.int.toLong() - before.int) <= 1)
    }
}
