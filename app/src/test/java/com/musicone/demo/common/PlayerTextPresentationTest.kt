package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerTextPresentationTest {
    @Test fun romanizedArtistAnnotationsAreRemovedAcrossArtistBoundaries() {
        assertEquals("米津玄師", playerPrimaryArtists("米津玄師（Kenshi Yonezu）"))
        assertEquals("ヨルシカ / 宇多田ヒカル、椎名林檎",
            playerPrimaryArtists("ヨルシカ (Yorushika) / 宇多田ヒカル（Utada Hikaru）、椎名林檎（Shiina Ringo）"))
        assertEquals("斉藤和義", playerPrimaryArtists("斉藤和義（Saitō Kazuyoshi）（サイトウカズヨシ）"))
        assertEquals("米津玄師 / YOASOBI (Japan) / (G)I-DLE",
            playerPrimaryArtists("米津玄師（Kenshi Yonezu） / YOASOBI (Japan) / (G)I-DLE"))
        assertEquals("ヨルシカ (Live) / 歌手（中文名） / （Yorushika）",
            playerPrimaryArtists("ヨルシカ (Live) / 歌手（中文名） / （Yorushika）"))
        assertEquals("ヨルシカ (G)Band", playerPrimaryArtists("ヨルシカ (G)Band"))
    }

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
        val original = MusicTrack("song", MusicSource.QQ, "夜に駆ける（ヨルニカケル）", "ヨルシカ（Yorushika）",
            "专辑", 180_000L, 0L, 0L, "封面", "")
        val enriched = original.copy(title = "夜に駆ける", artists = "ヨルシカ")
        assertEquals(PlayerTextPresentation("夜に駆ける", "ヨルシカ"), original.playerTextPresentation())
        assertEquals(original.playerTextPresentation(), enriched.playerTextPresentation())
        assertEquals(original.playerVisualKey(), enriched.playerVisualKey())
        assertEquals("夜に駆ける（ヨルニカケル）", original.title)
        assertEquals("ヨルシカ（Yorushika）", original.artists)
    }
}
