package com.musicone

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class AiRecommendationToolsTest {
    @Test fun directSourceSelectionRequiresNamingThatSource() {
        val names = listOf("qq", "QQ音乐")
        assertFalse(aiExplicitSourceRequested("推荐十首歌", names))
        assertFalse(aiExplicitSourceRequested("我喜欢QQ音乐的歌", names))
        assertFalse(aiExplicitSourceRequested("在QQ音乐里有会员", names))
        assertFalse(aiExplicitSourceRequested("我没有QQ会员，推荐十首歌", names))
        assertTrue(aiExplicitSourceRequested("用QQ音乐搜周杰伦", names))
        assertTrue(aiExplicitSourceRequested("只在QQ找歌", names))
        assertFalse(aiExplicitSourceRequested("可以跨音源找", names))
        assertFalse(aiExplicitSourceRequested("不要跨音源，用当前音源就好", names))
        assertFalse(aiExplicitSourceRequested("不要用QQ音乐搜", names))
        assertFalse(aiExplicitSourceRequested("可以跨音源，但不要用QQ音乐搜", names))
        assertFalse(aiExplicitSourceRequested("不要去QQ找，用当前音源", names))
        assertFalse(aiExplicitSourceRequested("可以跨音源，但不要用QQ音乐搜", listOf("kugou", "酷狗音乐")))
    }

    @Test fun genericCrossSourcePermissionNeverSelectsAnArbitrarySource() {
        listOf("可以跨音源找", "允许跨音源", "所有音源都搜", "其他音源也可以").forEach { request ->
            assertFalse(request, aiExplicitSourceRequested(request, listOf("qq", "QQ音乐")))
            assertTrue(aiExplicitSearchSourceIds(request, sourceIdentities, setOf("qq", "kugou")).isEmpty())
        }
        assertTrue(aiExplicitSourceRequested("可以跨音源，先用QQ搜", listOf("qq", "QQ音乐")))
    }

    @Test fun omittedSourceIdStillReportsExplicitOfflineSourceInsteadOfRoutingElsewhere() {
        val failure = runCatching {
            aiExplicitSearchSourceIds("只用QQ音乐搜周杰伦", sourceIdentities, setOf("kugou"))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("QQ音乐"))
        assertEquals(setOf("qq"), aiExplicitSearchSourceIds("只在QQ找歌", sourceIdentities, setOf("qq", "kugou")))
        assertTrue(runCatching {
            aiExplicitSearchSourceIds("只用自定义音乐搜", mapOf("custom" to listOf("custom", "自定义音乐")), emptySet())
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun excludedOfflineSourceDoesNotTurnIntoAnExplicitTarget() {
        assertTrue(aiExplicitSearchSourceIds("可以跨音源，但不要用QQ音乐搜", sourceIdentities, setOf("kugou")).isEmpty())
        assertFalse(aiExplicitSourceRequested("不要去QQ找", listOf("qq", "QQ音乐")))
        assertTrue(aiSourceExcluded("不要用网易", listOf("com.musicone.source.netease", "网易云音乐"), false))
    }

    private val sourceIdentities = mapOf("qq" to listOf("qq", "QQ音乐"), "kugou" to listOf("kugou", "酷狗音乐"))

    @Test fun temporaryPlaylistIsOnlyAViewOverProvidedTracks() {
        val track = MusicTrack("a", MusicSource.LOCAL, "歌名", "艺人", "专辑", 1000, 1, 2, "♪", "")
        val playlist = aiTemporaryPlaylist("ai-temporary:test", "午后", "轻松一点", listOf(track))
        assertEquals("ai-temporary:test", playlist.id)
        assertEquals("午后", playlist.title)
        assertEquals(listOf(track), playlist.tracks)
        assertFalse(playlist.isOwned)
    }

    @Test fun cardsUseValidatedTracksAndKeepUnknownReason() {
        val track = MusicTrack("a", MusicSource.LOCAL, "歌名", "艺人", "专辑", 1000, 1, 2, "♪", "")
        val unknown = track.copy(id = "b")
        val updated = track.copy(unavailableReason = "最新资料")
        val validation = AiPlaybackValidation(listOf(updated), emptyList(), listOf(JSONObject()
            .put("id", "b").put("status", "unknown").put("reason", "音源未能确认权限")))
        val cards = aiTracksWithAvailability(listOf(track, unknown), validation)
        assertEquals(updated, cards.first())
        assertFalse(cards.last().playable)
        assertEquals("音源未能确认权限", cards.last().unavailableReason)
    }

    @Test fun regenerationCanShowPlaylistButCannotPlayIt() {
        assertTrue(aiResponseOnlyToolAllowed("search_playlists"))
        assertTrue(aiResponseOnlyToolAllowed("show_playlist"))
        assertFalse(aiResponseOnlyToolAllowed("play_playlist"))
    }
}
