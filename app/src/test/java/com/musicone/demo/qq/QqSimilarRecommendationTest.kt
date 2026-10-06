package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QqSimilarRecommendationTest {
    @Test fun similarSeedsOnlyUseTheNewestValidRecentSongs() {
        val recent = (1..15).map { track(MusicSource.QQ, it.toString(), id = "qq-$it") }
        val candidates = qqSimilarRecentCandidates(
            listOf(track(MusicSource.NETEASE, "999"), track(MusicSource.QQ, "0")) + recent,
        )
        assertEquals(recent.take(QQ_RECENT_SONG_WINDOW_SIZE), candidates)
    }
    @Test
    fun similarRecommendationsRequireQqTrackAndNumericCatalogId() {
        assertTrue(canRequestQqSimilarRecommendation(track(MusicSource.QQ, "123")))
        assertFalse(canRequestQqSimilarRecommendation(track(MusicSource.QQ, "")))
        assertFalse(canRequestQqSimilarRecommendation(track(MusicSource.QQ, "0")))
        assertFalse(canRequestQqSimilarRecommendation(track(MusicSource.NETEASE, "123")))
    }

    @Test
    fun similarRecommendationGridAdaptsAcrossPhoneAndTabletWidths() {
        assertEquals(1, qqSimilarRecommendationColumns(360f))
        assertEquals(2, qqSimilarRecommendationColumns(720f))
        assertEquals(3, qqSimilarRecommendationColumns(1_080f))
        assertEquals(3, qqSimilarRecommendationRows(3, 1))
        assertEquals(2, qqSimilarRecommendationRows(3, 2))
        assertEquals(1, qqSimilarRecommendationRows(3, 3))
    }

    @Test
    fun recommendationTitleTracksPagerPositionLikeMiniPlayerText() {
        val position = qqSimilarRecommendationPagerPosition(0, .25f)
        val outgoing = pageTitleMotion(0, position)
        val incoming = pageTitleMotion(1, position)

        assertEquals(-.25f, outgoing.offsetFraction)
        assertEquals(.75f, outgoing.alpha)
        assertEquals(.75f, incoming.offsetFraction)
        assertEquals(.25f, incoming.alpha)
    }

    @Test
    fun refreshContinuesFromTheNewestPreparedRecommendationSeed() {
        val original = track(MusicSource.QQ, "10", id = "qq-original")
        val newest = track(MusicSource.QQ, "30", id = "qq-newest")
        val state = QqSimilarRecommendationUiState(
            baseTrack = original,
            recommendations = listOf(
                QqSimilarRecommendation(original, "第一组", emptyList()),
                QqSimilarRecommendation(original, "第二组", listOf(QqSimilarSong(newest))),
            ),
        )

        assertEquals(newest, qqSimilarRecommendationRefreshSeed(state))
        assertEquals(original, qqSimilarRecommendationRefreshSeed(state.copy(recommendations = emptyList())))
    }

    @Test
    fun playingAnotherTrackKeepsAnExistingRecommendationSession() {
        val recommendation = QqSimilarRecommendation(
            baseTrack = track(MusicSource.QQ, "10", id = "qq-base"),
            title = "测试推荐",
            songs = emptyList(),
        )
        val loadedState = QqSimilarRecommendationUiState(recommendations = listOf(recommendation))

        assertFalse(shouldConfigureQqSimilarRecommendation(sessionChanged = false, state = loadedState))
        assertTrue(shouldConfigureQqSimilarRecommendation(sessionChanged = true, state = loadedState))
    }

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
