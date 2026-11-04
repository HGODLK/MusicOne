package com.musicone.source.bilibili

import org.junit.Assert.*
import org.junit.Test

class BiliAudioQualityPolicyTest {
    private val normal = BiliAudioStream(30280, "https://example.invalid/normal")
    private val hiRes = BiliAudioStream(30251, "https://example.invalid/hires", codecs = "fLaC")
    private val dolby = BiliAudioStream(30250, "https://example.invalid/dolby", codecs = "ec-3")
    private val all = BiliPlayAudioResult(normal, hiRes, dolby, 180_000)
    private val playback = BiliSourcePlayback { error("这些策略不访问网络状态") }
    private fun select(quality: String, automatic: Boolean = true, decoder: Boolean = true,
        unlocked: Boolean = false, usb: Boolean = false, audio: BiliPlayAudioResult = all) =
        selectBiliAudio(audio, quality, automatic, decoder, unlocked, usb)

    @Test fun genericHighPreferencesMapToSupportedDolbyButNormalAlwaysStaysNormal() {
        for (requested in listOf("LOSSLESS", "HI_RES", "DOLBY", "HIGHER", "EXHIGH")) {
            assertEquals("DOLBY", select(requested).quality)
            assertEquals("HI_RES", select(requested, decoder = false).quality)
            assertEquals("HI_RES", select(requested, decoder = false, unlocked = true).quality)
            assertEquals("HI_RES", select(requested, usb = true).quality)
        }
        assertEquals("STANDARD", select("STANDARD").quality)
    }
    @Test fun biliPreferenceFollowsMainSourceTier() {
        for (quality in listOf("HI_RES", "LOSSLESS", "DOLBY")) assertEquals("DOLBY", playback.normalizePreferredQuality(null, quality))
        for (quality in listOf("EXHIGH", "STANDARD")) assertEquals("STANDARD", playback.normalizePreferredQuality(null, quality))
    }
    @Test fun supportedAutomaticPlaybackFallsBackFromDolbyToHiResThenNormal() {
        assertEquals(dolby, select("DOLBY").stream)
        assertEquals("HI_RES", select("DOLBY", audio = all.copy(dolbyAudio = null)).quality)
        assertEquals("STANDARD", select("DOLBY", audio = all.copy(dolbyAudio = null, hiResAudio = null)).quality)
    }
    @Test fun unlockAllowsManualDolbyButNeverPromotesUnsupportedAutomaticPlayback() {
        assertEquals("HI_RES", select("DOLBY", decoder = false, unlocked = true).quality)
        assertEquals("DOLBY", select("DOLBY", automatic = false, decoder = false, unlocked = true).quality)
        assertEquals("STANDARD", select("DOLBY", decoder = false, unlocked = true, audio = all.copy(hiResAudio = null)).quality)
        try {
            select("DOLBY", decoder = false, unlocked = true, audio = all.copy(defaultAudio = null, hiResAudio = null))
            fail("自动选择不能退回未支持的杜比音轨")
        } catch (_: PlatformApiException) { }
    }
    @Test fun explicitCompatibilityFallbackAndNormalRequestsAreNotPromotedAgain() {
        // 显式兼容回退不是自动选源；请求层将 automatic 设为 false。
        assertEquals("HI_RES", select("HI_RES", automatic = false).quality)
        assertEquals("STANDARD", select("STANDARD").quality)
        assertEquals("HI_RES", select("DOLBY", automatic = false, usb = true).quality)
    }
    @Test fun onlyRealBiliQualityFallbacksAreExposedAndOldPreferencesAreNormalized() {
        assertEquals(listOf("DOLBY", "HI_RES", "STANDARD"), playback.fallbackQualityIds("DOLBY"))
        assertFalse("LOSSLESS" in playback.fallbackQualityIds("DOLBY"))
        for (old in listOf(null, "LOSSLESS", "EXHIGH", "HIGHER")) assertEquals("HI_RES", playback.normalizePreferredQuality(old, null))
        assertEquals("DOLBY", playback.normalizePreferredQuality("DOLBY", null))
        assertEquals("STANDARD", playback.normalizePreferredQuality("STANDARD", null))
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
