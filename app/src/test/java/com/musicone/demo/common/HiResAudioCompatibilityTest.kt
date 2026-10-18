package com.musicone.demo

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class HiResAudioCompatibilityTest {
    @Test
    fun genericFlacDecodersArePreferredOverVendorImplementations() {
        assertEquals(0, hiResAudioDecoderRank("c2.android.flac.decoder"))
        assertEquals(1, hiResAudioDecoderRank("OMX.google.flac.decoder"))
        assertEquals(2, hiResAudioDecoderRank("c2.vendor.flac.decoder"))
    }

    @Test
    fun flacAlwaysReceivesCapacityForLargeLosslessFrames() {
        assertEquals(
            HI_RES_FLAC_MAX_INPUT_SIZE_BYTES,
            hiResCodecMaxInputSize(MimeTypes.AUDIO_FLAC, Format.NO_VALUE),
        )
        assertEquals(
            HI_RES_FLAC_MAX_INPUT_SIZE_BYTES,
            hiResCodecMaxInputSize(MimeTypes.AUDIO_FLAC, 32 * 1024),
        )
    }

    @Test
    fun largerExtractorCapacityIsNeverReduced() {
        val extractorCapacity = HI_RES_FLAC_MAX_INPUT_SIZE_BYTES * 2

        assertEquals(
            extractorCapacity,
            hiResCodecMaxInputSize(MimeTypes.AUDIO_FLAC, extractorCapacity),
        )
    }

    @Test
    fun nonFlacFormatsKeepTheirOriginalCapacity() {
        assertEquals(48 * 1024, hiResCodecMaxInputSize(MimeTypes.AUDIO_AAC, 48 * 1024))
    }

    @Test
    fun flacEffectiveBitDepthComesFromExtractorFormat() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_FLAC)
            .setPcmEncoding(C.ENCODING_PCM_24BIT)
            .build()

        assertEquals(24, sourceAudioBitDepth(format))
    }

    @Test
    fun flacMetadataPreservesPrecisionWhenTheDecoderContainerIsNotYetKnown() {
        for (depth in listOf(16, 24, 32)) {
            val header = ByteArray(42)
            "fLaC".toByteArray().copyInto(header)
            header[4] = 0x80.toByte(); header[7] = 34
            val packed = (176400L shl 44) or (1L shl 41) or ((depth - 1L) shl 36) or 176400L
            for (index in 18..25) header[index] = (packed ushr ((25 - index) * 8)).toByte()
            val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_FLAC)
                .setInitializationData(listOf(header)).build()
            assertEquals(depth, sourceAudioBitDepth(format))
        }
    }

    @Test
    fun compressedFormatsWithoutEffectiveBitDepthUseSixteenBitOutput() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_MPEG).build()

        assertEquals(16, sourceAudioBitDepth(format))
    }
}
