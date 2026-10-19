package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class BiliAudioQualityPolicyTest {
    private val normal = BiliAudioStream(30280, "https://example.invalid/normal")
    private val hiRes = BiliAudioStream(30251, "https://example.invalid/hires", codecs = "fLaC")
    private val dolby = BiliAudioStream(30250, "https://example.invalid/dolby", codecs = "ec-3")
    private val all = BiliPlayAudioResult(normal, hiRes, dolby, 180_000)
    private fun eligibility(supported: Boolean, unlocked: Boolean = false, usb: Boolean = false) =
        DolbyPlaybackEligibility(supported, unlocked, usbOutputEnabled = usb, usbAudioConnected = usb)

    @Test fun genericHighPreferencesMapToSupportedDolbyButNormalAlwaysStaysNormal() {
        for (requested in listOf(AudioQuality.LOSSLESS, AudioQuality.HI_RES, AudioQuality.DOLBY)) {
            assertEquals(AudioQuality.DOLBY, biliAutomaticQuality(requested, eligibility(true)))
            assertEquals(AudioQuality.HI_RES, biliAutomaticQuality(requested, eligibility(false)))
            assertEquals(AudioQuality.HI_RES, biliAutomaticQuality(requested, eligibility(false, unlocked = true)))
            assertEquals(AudioQuality.HI_RES, biliAutomaticQuality(requested, eligibility(true, usb = true)))
        }
        // B站只有标准/高音质/Dolby 三档，旧 192/320 首选也应按高音质请求。
        for (requested in listOf(AudioQuality.HIGHER, AudioQuality.EXHIGH)) {
            assertEquals(AudioQuality.DOLBY, biliAutomaticQuality(requested, eligibility(true)))
            assertEquals(AudioQuality.HI_RES, biliAutomaticQuality(requested, eligibility(false)))
        }
        assertEquals(AudioQuality.STANDARD, biliAutomaticQuality(AudioQuality.STANDARD, eligibility(true)))
    }

    @Test fun biliPreferenceFollowsMainSourceTier() {
        assertEquals(AudioQuality.DOLBY, biliPreferredFromMain(AudioQuality.HI_RES))
        assertEquals(AudioQuality.DOLBY, biliPreferredFromMain(AudioQuality.LOSSLESS))
        assertEquals(AudioQuality.STANDARD, biliPreferredFromMain(AudioQuality.EXHIGH))
        assertEquals(AudioQuality.STANDARD, biliPreferredFromMain(AudioQuality.STANDARD))
        assertEquals(AudioQuality.DOLBY, biliPreferredFromMain(AudioQuality.DOLBY))
    }

    @Test fun supportedAutomaticPlaybackFallsBackFromDolbyToHiResThenNormal() {
        val policy = eligibility(true)
        assertEquals(dolby, selectBiliAudio(all, AudioQuality.DOLBY, true, policy).stream)
        assertEquals(AudioQuality.HI_RES, selectBiliAudio(all.copy(dolbyAudio = null), AudioQuality.DOLBY, true, policy).quality)
        assertEquals(AudioQuality.STANDARD, selectBiliAudio(all.copy(dolbyAudio = null, hiResAudio = null), AudioQuality.DOLBY, true, policy).quality)
    }

    @Test fun unlockAllowsManualDolbyButNeverPromotesUnsupportedAutomaticPlayback() {
        val unlocked = eligibility(false, unlocked = true)
        assertTrue(unlocked.allowed)
        assertFalse(unlocked.automaticAllowed)
        assertEquals(AudioQuality.HI_RES, selectBiliAudio(all, AudioQuality.DOLBY, true, unlocked).quality)
        assertEquals(AudioQuality.DOLBY, selectBiliAudio(all, AudioQuality.DOLBY, false, unlocked).quality)
        assertEquals(AudioQuality.STANDARD,
            selectBiliAudio(all.copy(hiResAudio = null), AudioQuality.DOLBY, true, unlocked).quality)
        try {
            selectBiliAudio(all.copy(defaultAudio = null, hiResAudio = null), AudioQuality.DOLBY, true, unlocked)
            fail("自动选择不能退回未支持的杜比音轨")
        } catch (_: PlatformApiException) { }
    }

    @Test fun explicitCompatibilityFallbackAndNormalRequestsAreNotPromotedAgain() {
        assertEquals(AudioQuality.HI_RES, selectBiliAudio(all, AudioQuality.HI_RES, true, eligibility(true)).quality)
        assertEquals(AudioQuality.STANDARD, selectBiliAudio(all, AudioQuality.STANDARD, true, eligibility(true)).quality)
        assertEquals(AudioQuality.HI_RES, selectBiliAudio(all, AudioQuality.DOLBY, false, eligibility(true, usb = true)).quality)
    }

    @Test fun onlyThreeRealBiliQualitiesAreExposedAndOldPreferencesAreNormalized() {
        assertEquals(listOf(AudioQuality.STANDARD, AudioQuality.HI_RES, AudioQuality.DOLBY), biliAvailableQualities(all, eligibility(true)))
        assertFalse(AudioQuality.LOSSLESS in playerQualitiesForSource(MusicSource.BILIBILI))
        assertFalse(AudioQuality.LOSSLESS in biliAvailableQualities(all, eligibility(false, unlocked = true)))
        assertEquals("Norm", AudioQuality.STANDARD.displayCompactLabel(MusicSource.BILIBILI))
        assertEquals("普通", AudioQuality.STANDARD.displayLabel(MusicSource.BILIBILI))
        assertEquals(AudioQuality.HI_RES, normalizedQualityPreference(MusicSource.BILIBILI, AudioQuality.LOSSLESS))
        assertEquals(AudioQuality.HI_RES, normalizedQualityPreference(MusicSource.BILIBILI, null))
        assertEquals(AudioQuality.DOLBY, normalizedQualityPreference(MusicSource.BILIBILI, AudioQuality.DOLBY))
        assertEquals(AudioQuality.HI_RES, normalizedQualityPreference(MusicSource.BILIBILI, AudioQuality.EXHIGH))
        assertEquals(AudioQuality.HI_RES, normalizedQualityPreference(MusicSource.BILIBILI, AudioQuality.HIGHER))
        assertEquals(AudioQuality.EXHIGH, normalizedQualityPreference(MusicSource.QQ, null))
        assertEquals(AudioQuality.LOSSLESS, normalizedQualityPreference(MusicSource.NETEASE, AudioQuality.DOLBY))
    }

    @Test fun cachedDolbyCannotBypassTheAutomaticDecoderGateAndNormalDoesNotBorrowHiRes() {
        val requested = biliAutomaticQuality(AudioQuality.DOLBY, eligibility(false, unlocked = true))
        assertFalse(biliCachedQualityAllowed(requested, AudioQuality.DOLBY, allowOther = true))
        assertTrue(biliCachedQualityAllowed(requested, AudioQuality.HI_RES, allowOther = false))
        assertFalse(biliCachedQualityAllowed(AudioQuality.STANDARD, AudioQuality.HI_RES, allowOther = true))
        assertTrue(biliCachedQualityAllowed(AudioQuality.DOLBY, AudioQuality.DOLBY, allowOther = false))
    }

    @Test fun biliAudioSpecificationsUseActualCodecWithoutInventedSamplingRate() {
        assertEquals("AAC", biliStreamCodecLabel(normal, false))
        assertEquals("FLAC", biliStreamCodecLabel(hiRes, false))
        assertEquals("Dolby Atmos", biliStreamCodecLabel(dolby, true))
        assertEquals("Opus", biliStreamCodecLabel(normal.copy(codecs = "opus"), false))
        val manifest = BiliDashBuilder.buildAudioOnlyManifest(hiRes, 180_000)
        assertFalse(manifest.contains("audioSamplingRate"))
        assertTrue(manifest.contains("fLaC"))
    }
}
