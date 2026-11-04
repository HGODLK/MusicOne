package com.musicone

import com.musicone.source.sdk.*
import com.musicone.sourceintegration.*
import org.junit.Assert.*
import org.junit.Test

class SourceCustomQualityTest {
    private val source = MusicSource.fromId("org.example.custom-quality")
    private val catalog = object : SourceQualityCatalog {
        override val qualityCatalog = listOf(
            SourceQualityDefinition("spatial-a", "空间 A", "空间A", "音源提供的空间处理", "LOSSLESS"),
            SourceQualityDefinition("spatial-b", "空间 B", "空间B", "另一种空间处理", "LOSSLESS"),
            SourceQualityDefinition("plain", "普通", description = "原始普通档", standardQualityId = "STANDARD"),
        )
        override val defaultCatalogQualityId = "spatial-b"
        override fun catalogFallbackQualityIds(preferredId: String) = when (preferredId) {
            "spatial-a" -> listOf("plain", "spatial-b")
            "spatial-b" -> listOf("plain")
            else -> emptyList()
        }
    }

    @Test fun standardConstantsRetainNamesOrderAndProviderAliases() {
        assertSame(AudioQuality.LOSSLESS, AudioQuality.valueOf("LOSSLESS"))
        assertEquals(3, AudioQuality.LOSSLESS.ordinal)
        assertEquals(6, AudioQuality.entries.size)
        assertSame(AudioQuality.EXHIGH, AudioQuality.fromProvider("exhigh", 0))
    }

    @Test fun sameStandardMappingKeepsIndependentIdsAndPresentation() {
        val qualities = sourceCatalogQualities(source, catalog)
        assertNotEquals(qualities[0], qualities[1])
        assertEquals(AudioQuality.LOSSLESS, qualities[0].standardQuality)
        assertEquals(listOf("spatial-a", "spatial-b", "plain"), qualities.map { it.qualityId })
        assertEquals("空间 A", qualities[0].displayLabel(source))
        assertEquals("音源提供的空间处理", qualities[0].displayDescription())
        assertEquals(qualities, visiblePlayerQualities(qualities))
        assertNotEquals(qualities[0], AudioQuality.custom("org.example.other", "spatial-a"))
    }

    @Test fun explicitFallbackOrderWinsOverCatalogOrderAndStandardMapping() {
        val first = sourceCatalogQualities(source, catalog).first()
        assertEquals(listOf("spatial-a", "plain", "spatial-b"),
            sourceCatalogFallback(source, catalog, first).map { it.qualityId })
    }

    @Test fun defaultCatalogIdMigratesUnmappedLegacyPreference() {
        assertEquals("spatial-b", sourceCatalogFallback(source, catalog, AudioQuality.HI_RES).first().qualityId)
    }

    @Test fun missingPreferenceUsesDeclaredDefaultEvenWhenFirstTierHasNoStandardMapping() {
        val unmapped = object : SourceQualityCatalog by catalog {
            override val qualityCatalog = catalog.qualityCatalog.map { it.copy(standardQualityId = null) }
        }
        assertEquals("spatial-b", sourceCatalogPreferred(source, unmapped, null).qualityId)
    }

    @Test fun customIdentityRoundTripsReservedCharactersWithoutCatalogAndCannotBecomeStandard() {
        val custom = AudioQuality.custom(source.id, "空间:LOSSLESS /+%", "独立档")
        assertEquals(custom, AudioQuality.valueOf(custom.name))
        assertEquals(custom, sourceAudioQuality(custom.name, source))
        assertNotEquals(AudioQuality.LOSSLESS, AudioQuality.custom(source.id, "LOSSLESS"))
        assertEquals("空间:LOSSLESS /+%", AudioQuality.valueOf(custom.name).qualityId)
    }

    @Test fun realResourceDolbyEvidenceIsUsedOnlyForCustomCatalogResources() {
        val custom = AudioQuality.custom(source.id, "cinema", "影院")
        val resource = PlaybackSource("https://example.invalid/audio", custom, custom, 0, "mp4", false,
            mimeType = "audio/mp4; codecs=\"ec-3\"")
        assertTrue(resource.requiresDolbyCapability())
        assertTrue(declaredDolbyResource("audio/eac3-joc", "mp4"))
        assertTrue(declaredDolbyResource(null, "eac3"))
        assertFalse(resource.copy(actualQuality = AudioQuality.LOSSLESS).requiresDolbyCapability())
        assertFalse(resource.copy(mimeType = "audio/mp4").requiresDolbyCapability())
        assertFalse(declaredDolbyResource("audio/mp4", "Dolby影院音质"))
    }

    @Test fun changedCatalogBlankLabelsRetainIdentityWithNeutralPresentation() {
        val changed = object : SourceQualityCatalog by catalog {
            override val qualityCatalog = catalog.qualityCatalog.map { it.copy(label = "", compactLabel = "") }
        }
        val values = sourceCatalogQualities(source, changed)
        assertEquals("spatial-a", values.first().qualityId)
        assertEquals("无损音质", values.first().label)
        assertEquals("无损", values.first().compactLabel)
        val unnamed = object : SourceQualityCatalog by catalog {
            override val qualityCatalog = listOf(SourceQualityDefinition("spatial-b"))
        }
        assertEquals("默认音质", sourceCatalogQualities(source, unnamed).single().label)
        val multiple = object : SourceQualityCatalog by catalog {
            override val qualityCatalog = listOf(SourceQualityDefinition("spatial-a"), SourceQualityDefinition("spatial-b"))
        }
        assertEquals(listOf("音质 1", "音质 2"), sourceCatalogQualities(source, multiple).map { it.label })
    }

    @Test(expected = IllegalArgumentException::class)
    fun undeclaredFallbackCannotBeSilentlyReplacedWithStandardTier() {
        val invalid = object : SourceQualityCatalog by catalog {
            override fun catalogFallbackQualityIds(preferredId: String) = listOf("not-declared")
        }
        sourceCatalogFallback(source, invalid, AudioQuality.STANDARD)
    }
}
