package com.musicone

import androidx.media3.common.C
import androidx.media3.common.Format
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class LgHiResPolicyTest {
    private fun pcm(encoding: Int, rate: Int = 44100, channels: Int = 2) = Format.Builder()
        .setSampleMimeType("audio/raw").setPcmEncoding(encoding).setSampleRate(rate).setChannelCount(channels).build()

    @Test fun manufacturerVisibilityAndModelEligibilityAreSeparate() {
        listOf("LGE", "LG", " lg electronics ").forEach { assertTrue(isLgAudioDevice(it)) }
        listOf("Samsung", "Google", "LGE-like").forEach { assertFalse(isLgAudioDevice(it)) }
        listOf("LM-G710N", "LM-G710ULM", "LM-G820UM", "LM-G820N", "LG G8 ThinQ").forEach { assertTrue(isLgG7G8(it)) }
        listOf("LM-G850", "LM-V500", "Pixel 8", "G7", "LM-G710N fake").forEach { assertFalse(isLgG7G8(it)) }
    }

    @Test fun sourceSampleRateIsNeverChangedAndFullS32KeepsAllBits() {
        for (rate in listOf(44100, 48000, 88200, 96000, 176400, 192000)) {
            assertEquals(24, lgPcmOutputDepth(pcm(C.ENCODING_PCM_16BIT, rate), 16))
            assertEquals(24, lgPcmOutputDepth(pcm(C.ENCODING_PCM_32BIT, rate), 24))
            assertEquals(32, lgPcmOutputDepth(pcm(C.ENCODING_PCM_32BIT, rate), 32))
        }
        assertNull(lgPcmOutputDepth(pcm(C.ENCODING_PCM_16BIT, channels = 6), 16))
        assertNull(lgPcmOutputDepth(pcm(C.ENCODING_PCM_16BIT, rate = -1), 16))
        assertNull(lgPcmOutputDepth(pcm(C.ENCODING_AC3), 16))
    }

    @Test fun internalDacUsesThePhoneModelInsteadOfTheGenericHeadphonePortName() {
        assertEquals("LG G7 ThinQ", lgAudioDeviceName("LM-G710N"))
        assertEquals("LG G8 ThinQ", lgAudioDeviceName("LM-G820UM"))
        assertEquals("LG G8 ThinQ", lgAudioDeviceName("LG G8 ThinQ"))
        assertEquals("LM-V500", lgAudioDeviceName("LM-V500"))
    }

    @Test fun sixteenBitSamplesAreOnlyZeroPaddedIncludingNegativeFullScale() {
        val source = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        listOf(Short.MIN_VALUE, (-1).toShort(), 0.toShort(), 1.toShort(), Short.MAX_VALUE).forEach(source::putShort)
        source.flip()
        val dest = ByteBuffer.allocate(15)
        assertEquals(10, packLgPcm(source, C.ENCODING_PCM_16BIT, 24, dest))
        assertEquals(0, source.position())
        assertArrayEquals(byteArrayOf(0, 0, -128, 0, -1, -1, 0, 0, 0, 0, 1, 0, 0, -1, 127), dest.array())
    }

    @Test fun packedTwentyFourBitAndLeftAlignedS32KeepIdenticalSamples() {
        val packed = byteArrayOf(0x12, 0x34, 0x56, -1, -1, -1, 0, 0, -128)
        val out = ByteBuffer.allocate(9)
        assertEquals(9, packLgPcm(ByteBuffer.wrap(packed), C.ENCODING_PCM_24BIT, 24, out))
        assertArrayEquals(packed, out.array())
        val s32 = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        listOf(0x56341200, -256, Int.MIN_VALUE).forEach(s32::putInt)
        s32.flip()
        assertEquals(12, packLgPcm(s32, C.ENCODING_PCM_32BIT, 24, out))
        assertArrayEquals(packed, out.array())
    }

    @Test fun fullS32IsNotTruncatedAndInputPositionIsRespected() {
        val source = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        listOf(0, Int.MIN_VALUE, 0x12345678, Int.MAX_VALUE).forEach(source::putInt)
        source.flip(); source.position(4)
        val out = ByteBuffer.allocate(12)
        assertEquals(12, packLgPcm(source, C.ENCODING_PCM_32BIT, 32, out))
        assertArrayEquals(source.array().copyOfRange(4, 16), out.array())
        assertEquals(4, source.position())
    }

    @Test fun floatPackingPreservesTwentyFourBitValuesAndClampsInvalidPeaks() {
        val source = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        listOf(-1f, -.5f, 0f, .5f, 1f, Float.NaN).forEach(source::putFloat)
        source.flip()
        val out = ByteBuffer.allocate(18)
        assertEquals(24, packLgPcm(source, C.ENCODING_PCM_FLOAT, 24, out))
        assertArrayEquals(byteArrayOf(0, 0, -128, 0, 0, -64, 0, 0, 0, 0, 0, 64, -1, -1, 127, 0, 0, 0), out.array())
    }

    @Test fun boundedPackingDoesNotConsumeUnsubmittedInput() {
        val source = ByteBuffer.wrap(byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0))
        val out = ByteBuffer.allocate(6)
        assertEquals(4, packLgPcm(source, C.ENCODING_PCM_16BIT, 24, out))
        assertArrayEquals(byteArrayOf(0, 1, 0, 0, 2, 0), out.array())
        assertEquals(0, source.position())
    }
}
