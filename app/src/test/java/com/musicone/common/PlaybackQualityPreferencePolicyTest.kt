package com.musicone

import org.junit.Assert.*
import org.junit.Test

class PlaybackQualityPreferencePolicyTest {
    @Test fun crossSourcePreferencePassesStandardCapabilityWithoutChangingOriginalIdentity() {
        val quality = AudioQuality.custom("org.example.main", "studio", "录音室", standard = AudioQuality.LOSSLESS)
        assertEquals("LOSSLESS", mainPlaybackQualityCapabilityId(quality))
        assertEquals("studio", quality.qualityId)
        assertEquals("HI_RES", mainPlaybackQualityCapabilityId(AudioQuality.HI_RES))
        assertEquals("STANDARD", mainPlaybackQualityCapabilityId(
            AudioQuality.custom("org.example.main", "DOLBY", standard = AudioQuality.STANDARD)))
    }

    @Test fun unmappedPrivateIdsCannotMasqueradeAsAnotherSourcesStandardPreference() {
        assertNull(mainPlaybackQualityCapabilityId(AudioQuality.custom("org.example.main", "LOSSLESS")))
        assertNull(mainPlaybackQualityCapabilityId(AudioQuality.custom("org.example.main", "spatial")))
    }

    @Test fun mobileLimitRecoversLabelAndCapabilityWhenCatalogAppearsAfterStartup() {
        val sourceId = "org.example.main"
        val saved = AudioQuality.custom(sourceId, "small", "未知音质", "未知")
        var values: List<AudioQuality>? = null
        val lookup: (String) -> List<AudioQuality>? = { requested -> assertEquals(sourceId, requested); values }
        assertSame(saved, refreshMobileQuality(saved, lookup))
        values = listOf(AudioQuality.custom(sourceId, "small", "节流档", "节流", standard = AudioQuality.STANDARD))
        val restored = requireNotNull(refreshMobileQuality(saved, lookup))
        assertEquals(saved, restored)
        assertEquals(saved.name, restored.name)
        assertEquals("节流档", restored.label)
        assertEquals(AudioQuality.STANDARD, restored.standardQuality)
        assertEquals(AudioQuality.STANDARD, mobileLimitedQuality(AudioQuality.LOSSLESS, restored, "QQ") { emptyList() })
    }

    @Test fun sameSourceMobileLimitKeepsIndependentIdsWithTheSameStandardCapability() {
        val sourceId = "org.example.main"
        val first = AudioQuality.custom(sourceId, "first", standard = AudioQuality.LOSSLESS)
        val second = AudioQuality.custom(sourceId, "second", standard = AudioQuality.LOSSLESS)
        val limit = requireNotNull(refreshMobileQuality(AudioQuality.custom(sourceId, "second")) { listOf(first, second) })
        assertSame(second, mobileLimitedQuality(first, limit, sourceId) { listOf(first, second) })
    }

    @Test fun crossSourceCustomLimitUsesCapabilityAndLeavesUnmappedTargetsToTheirOwnPreference() {
        val sourceId = "org.example.target"
        val high = AudioQuality.custom(sourceId, "studio", standard = AudioQuality.LOSSLESS)
        val low = AudioQuality.custom(sourceId, "small", standard = AudioQuality.STANDARD)
        val otherLimit = AudioQuality.custom("org.example.other", "small", standard = AudioQuality.STANDARD)
        assertSame(low, mobileLimitedQuality(high, otherLimit, sourceId) { listOf(high, low) })
        assertSame(high, mobileLimitedQuality(high, AudioQuality.custom("org.example.other", "STANDARD"), sourceId) { listOf(high, low) })
        val unmapped = AudioQuality.custom(sourceId, "private")
        assertSame(unmapped, mobileLimitedQuality(unmapped, AudioQuality.STANDARD, sourceId) { listOf(unmapped) })
    }

    @Test fun removedTierDoesNotKeepAnOutdatedStandardMappingAndLegacyLimitsSkipCatalog() {
        val saved = AudioQuality.custom("org.example.main", "old", "旧档", standard = AudioQuality.DOLBY)
        val removed = requireNotNull(refreshMobileQuality(saved) { emptyList() })
        assertEquals(saved, removed)
        assertEquals("未知音质", removed.label)
        assertNull(removed.standardQuality)
        assertSame(AudioQuality.STANDARD, refreshMobileQuality(AudioQuality.STANDARD) { error("标准档无需查询目录") })
        assertNull(refreshMobileQuality(null) { error("未设置上限无需查询目录") })
        assertEquals(AudioQuality.STANDARD, mobileLimitedQuality(AudioQuality.LOSSLESS, AudioQuality.STANDARD, "QQ") { emptyList() })
        assertEquals(AudioQuality.LOSSLESS, mobileLimitedQuality(AudioQuality.LOSSLESS, null, "QQ") { emptyList() })
    }
}
