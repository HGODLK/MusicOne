package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainSourceSimilarRecommendationTest {
    @Test fun similarSeedsOnlyUseTheNewestValidRecentSongs() {
        val recent = (1..15).map { track(MusicSource.QQ, it.toString(), id = "qq-$it") }
        val candidates = mainSourceSimilarRecentCandidates(
            listOf(track(MusicSource.LOCAL, "999")) + recent,
        )
        assertEquals(recent.take(MAIN_SOURCE_RECENT_WINDOW_SIZE), candidates)
    }
    @Test
    fun similarRecommendationsAcceptRemoteSourcesAndRejectLocalTracks() {
        assertTrue(canRequestMainSourceSimilarRecommendation(track(MusicSource.QQ, "123")))
        assertTrue(canRequestMainSourceSimilarRecommendation(track(MusicSource.QQ, "")))
        assertFalse(canRequestMainSourceSimilarRecommendation(track(MusicSource.LOCAL, "0")))
        assertTrue(canRequestMainSourceSimilarRecommendation(track(MusicSource.NETEASE, "123")))
    }

    @Test
    fun similarRecommendationGridAdaptsAcrossPhoneAndTabletWidths() {
        assertEquals(1, mainSourceSimilarRecommendationColumns(360f))
        assertEquals(2, mainSourceSimilarRecommendationColumns(720f))
        assertEquals(3, mainSourceSimilarRecommendationColumns(1_080f))
        assertEquals(3, mainSourceSimilarRecommendationRows(3, 1))
        assertEquals(2, mainSourceSimilarRecommendationRows(3, 2))
        assertEquals(1, mainSourceSimilarRecommendationRows(3, 3))
    }

    @Test
    fun recommendationTitleTracksPagerPositionLikeMiniPlayerText() {
        val position = mainSourceSimilarRecommendationPagerPosition(0, .25f)
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
        val state = MainSourceSimilarRecommendationUiState(
            baseTrack = original,
            recommendations = listOf(
                MainSourceSimilarRecommendation(original, "第一组", emptyList()),
                MainSourceSimilarRecommendation(original, "第二组", listOf(MainSourceSimilarSong(newest))),
            ),
        )

        assertEquals(newest, mainSourceSimilarRecommendationRefreshSeed(state))
        assertEquals(original, mainSourceSimilarRecommendationRefreshSeed(state.copy(recommendations = emptyList())))
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
