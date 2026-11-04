package com.musicone.source.qq.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QqSimilarRecommendationTest {
    @Test
    fun genericRemoteTitleUsesCurrentSeedSong() {
        assertEquals(
            "听「心拍数＃0822」也会喜欢",
            qqSimilarRecommendationTitle("猜你也会喜欢", "心拍数＃0822"),
        )
        assertEquals(
            "听「心拍数＃0822」也会喜欢",
            qqSimilarRecommendationTitle(" 你可能会喜欢 ", "心拍数＃0822"),
        )
    }

    @Test
    fun longSeedSongIsTruncatedBeforeTheFixedRecommendationCopy() {
        assertEquals(
            "听「一二三四五六七八九十甲乙…」也会喜欢",
            qqSimilarRecommendationTitle("猜你也会喜欢", "一二三四五六七八九十甲乙丙丁戊己"),
        )
    }

    @Test
    fun specificRemoteTitleIsPreserved() {
        assertEquals(
            "听过《深海》的人也在听",
            qqSimilarRecommendationTitle("听过《深海》的人也在听", "心拍数＃0822"),
        )
    }

    private fun track(source: MusicSource, catalogId: String, id: String = "${source.name.lowercase()}-track") = MusicTrack(
        id = id,
        source = source,
        title = "测试歌曲",
        artists = "歌手",
        album = "专辑",
        durationMs = 180_000L,
        artworkStart = 0L,
        artworkEnd = 0L,
        artworkMark = "测",
        previewUrl = "",
        catalogId = catalogId,
    )
}
