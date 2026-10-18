package com.musicone.demo

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.*
import org.junit.Test
import com.decent.usbaudio.UsbAudioStreamProfile

class UsbPcmFormatTest {
    private fun profile(bits: Int) = UsbAudioStreamProfile(1, bits / 8, bits, bits / 8, 1, 0x81, 400,
        8000, 8000, 0, 7, true)

    @Test fun supportedPcmKeepsItsSourceRateAndPrecisionBeforeAnyCompatibilityPlan() {
        val original = profile(24)
        val compatible = profile(32)
        val plans = usbPcmOutputPlans(96000, 24) { rate, bits ->
            if (rate == 96000 && bits == 24) original else if (rate == 192000 && bits == 32) compatible else null
        }
        assertEquals(listOf(96000, 192000), plans.map { it.sampleRate }.toList())
        assertFalse(plans.first().converted)
        assertTrue(plans.last().converted)
    }

    @Test fun unsupported176400UsesOnlyAdvertised32bit192000AndNeverPretends24bitIs32bit() {
        val plans = usbPcmOutputPlans(176400, 24) { rate, bits ->
            if (rate == 192000 && bits == 32) profile(32) else null
        }
        assertEquals(192000, plans.single().sampleRate)
        assertEquals(32, plans.single().profile.bitDepth)
        assertTrue(plans.single().converted)
        assertFalse(usbPcmOutputPlans(176400, 24) { _, _ -> null }.any())
    }

    @Test fun dsdAlreadyConvertedTo32bit192000DoesNotCreateASecondConversionPlan() {
        assertEquals(1, usbPcmOutputPlans(192000, 32) { _, _ -> profile(32) }.count())
    }

    @Test fun workingSourceProfileDoesNotQueryUnusedCompatibilityClocks() {
        val requests = mutableListOf<Int>()
        val plans = usbPcmOutputPlans(96000, 24) { rate, _ -> requests += rate; profile(24) }
        assertEquals(96000, plans.first().sampleRate)
        assertEquals(listOf(96000), requests)
    }

    @Test fun invalidClockCannotStartDespiteMatchingSampleRateReadback() {
        var started = false
        assertFalse(configureUsbPcmOutput(192000, { true }, { true }, { true }, { 192000 },
            { started = true; true }, clockValid = { false }))
        assertFalse(started)
    }
    @Test fun decoderContainersPreserveSourcePrecisionForEveryPcmEncoding() {
        assertEquals(24, usbPcmSourceBitDepth(format(C.ENCODING_PCM_32BIT), 24))
        assertEquals(24, usbPcmSourceBitDepth(format(C.ENCODING_PCM_FLOAT), 24))
        assertEquals(16, usbPcmSourceBitDepth(format(C.ENCODING_PCM_32BIT), 16))
        assertEquals(16, usbPcmSourceBitDepth(format(C.ENCODING_PCM_FLOAT), 16))
        assertEquals(24, usbPcmSourceBitDepth(format(C.ENCODING_PCM_24BIT), 24))
        assertEquals(16, usbPcmSourceBitDepth(format(C.ENCODING_PCM_16BIT), 24))
        assertEquals(32, usbPcmSourceBitDepth(format(C.ENCODING_PCM_32BIT), 32))
    }

    @Test fun nativeDsdAndLosslessLabelsOverrideThePreviousTrackDepth() {
        for (depth in listOf(16, 24, 32)) {
            val pcm = format(C.ENCODING_PCM_32BIT).buildUpon().setLabel("effective-depth:$depth").build()
            assertEquals(depth, usbPcmSourceBitDepth(pcm, 16))
        }
    }

    @Test fun sampleRateIsCheckedAfterProfileSelectionBeforeStarting() {
        val calls = mutableListOf<String>()
        assertTrue(configureUsbPcmOutput(176400,
            { calls += "idle"; true }, { calls += "set:$it"; true },
            { calls += "profile"; true }, { calls += "read"; 176400 },
            { calls += "start"; true }))
        assertEquals(listOf("idle", "set:176400", "profile", "read", "start"), calls)
    }

    @Test fun rejectedOrMismatchedRatesNeverStartAnExclusiveStream() {
        for ((accepted, actual) in listOf(false to -1, false to 48000, true to 48000)) {
            var started = false
            assertFalse(configureUsbPcmOutput(176400, { true }, { accepted }, { true }, { actual },
                { started = true; true }))
            assertFalse(started)
        }
    }

    @Test fun fixedClockOrUnreadableClockUsesOnlyPositiveConfigurationEvidence() {
        assertTrue(configureUsbPcmOutput(96000, { true }, { false }, { true }, { 96000 }, { true }))
        assertFalse(configureUsbPcmOutput(96000, { true }, { true }, { true }, { -1 }, { true }))
    }

    @Test fun interfaceAndStartFailuresCannotReportSuccess() {
        assertFalse(configureUsbPcmOutput(44100, { false }, { error("不应设置时钟") },
            { error("不应启用接口") }, { error("不应回读") }, { error("不应启动") }))
        assertFalse(configureUsbPcmOutput(44100, { true }, { true }, { false },
            { error("不应回读") }, { error("不应启动") }))
        assertFalse(configureUsbPcmOutput(44100, { true }, { true }, { true }, { 44100 }, { false }))
    }

    private fun format(encoding: Int) = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(encoding).setSampleRate(176400).setChannelCount(2).build()
}
