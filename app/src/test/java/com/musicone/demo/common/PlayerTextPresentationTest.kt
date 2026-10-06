package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerTextPresentationTest {
    @Test fun artistsLoseKanaAnnotationsBeforeTheFirstPresentation() {
        assertEquals("YOASOBI", playerPrimaryArtists("YOASOBI（ヨアソビ）"))
        assertEquals("米津玄師", playerPrimaryArtists("米津玄師 (よねづけんし)"))
        assertEquals("歌手", playerPrimaryArtists("歌手（ｶﾀｶﾅ）"))
    }

    @Test fun everyArtistIsCleanedWithoutChangingTheSeparators() {
        assertEquals("歌手甲 / 歌手乙、歌手丙",
            playerPrimaryArtists("歌手甲 (カタカナ) / 歌手乙（ひらがな）、歌手丙（カタカナ）"))
    }

    @Test fun artistNamesAndNonPhoneticParenthesesArePreserved() {
        assertEquals("ヨルシカ / (G)I-DLE / 歌手（中文名）",
            playerPrimaryArtists("ヨルシカ / (G)I-DLE / 歌手（中文名）"))
        assertEquals("（カタカナ）", playerPrimaryArtists("（カタカナ）"))
        assertEquals("", playerPrimaryArtists(""))
    }

    @Test fun cleanedPresentationIsStableAcrossMetadataEnrichmentAndLeavesTheSourceIntact() {
        val original = MusicTrack("song", MusicSource.QQ, "夜に駆ける（ヨルニカケル）", "YOASOBI（ヨアソビ）",
            "专辑", 180_000L, 0L, 0L, "封面", "")
        val enriched = original.copy(title = "夜に駆ける", artists = "YOASOBI")
        assertEquals(PlayerTextPresentation("夜に駆ける", "YOASOBI"), original.playerTextPresentation())
        assertEquals(original.playerTextPresentation(), enriched.playerTextPresentation())
        assertEquals(original.playerVisualKey(), enriched.playerVisualKey())
        assertEquals("夜に駆ける（ヨルニカケル）", original.title)
        assertEquals("YOASOBI（ヨアソビ）", original.artists)
    }
}
