package com.musicone.demo

import androidx.media3.common.C
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmSoftwareGainTest {
    @Test
    fun scalesSupportedPcmWithoutChangingUnityGain() {
        val floatPcm = floatArrayOf(1f, -.5f)
        PcmSoftwareGain.apply(floatPcm, .5f)
        assertArrayEquals(floatArrayOf(.5f, -.25f), floatPcm, 0f)

        val pcm16 = encode16(Short.MAX_VALUE.toInt(), Short.MIN_VALUE.toInt())
        PcmSoftwareGain.apply(pcm16, C.ENCODING_PCM_16BIT, .5f)
        assertEquals(listOf(16_384, -16_384), decode16(pcm16))

        val pcm24 = encode24(0x7FFFFF, -0x800000)
        PcmSoftwareGain.apply(pcm24, C.ENCODING_PCM_24BIT, .5f)
        assertEquals(listOf(0x400000, -0x400000), decode24(pcm24))

        val pcm32 = encode32(Int.MAX_VALUE, Int.MIN_VALUE)
        PcmSoftwareGain.apply(pcm32, C.ENCODING_PCM_32BIT, .5f)
        assertEquals(listOf(1_073_741_824, -1_073_741_824), decode32(pcm32))

        val unchanged = encode24(123_456, -654_321)
        val original = unchanged.copyOf()
        PcmSoftwareGain.apply(unchanged, C.ENCODING_PCM_24BIT, 1f)
        assertArrayEquals(original, unchanged)
    }

    private fun encode16(vararg samples: Int) = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample shr 8).toByte()
        }
    }

    private fun decode16(bytes: ByteArray) = bytes.toList().chunked(2).map { sample ->
        ((sample[0].toInt() and 0xFF) or (sample[1].toInt() shl 8)).toShort().toInt()
    }

    private fun encode24(vararg samples: Int) = ByteArray(samples.size * 3).also { bytes ->
        samples.forEachIndexed { index, sample ->
            bytes[index * 3] = sample.toByte()
            bytes[index * 3 + 1] = (sample shr 8).toByte()
            bytes[index * 3 + 2] = (sample shr 16).toByte()
        }
    }

    private fun decode24(bytes: ByteArray) = bytes.toList().chunked(3).map { sample ->
        val raw = (sample[0].toInt() and 0xFF) or
            ((sample[1].toInt() and 0xFF) shl 8) or
            ((sample[2].toInt() and 0xFF) shl 16)
        if (raw and 0x800000 != 0) raw or -0x1000000 else raw
    }

    private fun encode32(vararg samples: Int) = ByteArray(samples.size * 4).also { bytes ->
        samples.forEachIndexed { index, sample ->
            bytes[index * 4] = sample.toByte()
            bytes[index * 4 + 1] = (sample shr 8).toByte()
            bytes[index * 4 + 2] = (sample shr 16).toByte()
            bytes[index * 4 + 3] = (sample shr 24).toByte()
        }
    }

    private fun decode32(bytes: ByteArray) = bytes.toList().chunked(4).map { sample ->
        (sample[0].toInt() and 0xFF) or
            ((sample[1].toInt() and 0xFF) shl 8) or
            ((sample[2].toInt() and 0xFF) shl 16) or
            (sample[3].toInt() shl 24)
    }
}
