package com.musicone.demo

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HyperOsIslandPayloadTest {
    private val summary = HyperOsIslandSummary("落俗", "李", "歌落俗")
    private fun params(value: HyperOsIslandSummary = summary, sequence: Long = 1, bringToFront: Boolean = true) =
        JSONObject(hyperOsIslandPayload(value, sequence, bringToFront))

    @Test fun standardTemplateWrapsTheSameBoundedLyricButKeepsExpandedMetadataFreeOfLyrics() {
        val frame = HyperOsIslandFrame(SystemPlaybackKey("opaque", 1), summary, "完整歌曲名称", "完整歌手", 7, 100)
        val root = JSONObject(hyperOsStandardIslandPayload(frame, 3, false))
        assertEquals(setOf("param_v2"), root.keys().asSequence().toSet())
        val params = root.getJSONObject("param_v2")
        assertEquals(1, params.getInt("protocol"))
        assertEquals(summary.lyric, params.getString("ticker"))
        assertEquals("完整歌曲名称", params.getJSONObject("baseInfo").getString("title"))
        assertEquals("完整歌手", params.getJSONObject("baseInfo").getString("content"))
        assertFalse(params.getJSONObject("baseInfo").toString().contains(summary.lyric))
        assertEquals(summary.lyric, params.getJSONObject("param_island").getJSONObject("bigIslandArea")
            .getJSONObject("textInfo").getString("title"))
    }

    @Test fun customPayloadEnablesExpansionWithoutWrappingItInTheStandardTemplateProtocol() {
        val params = params()
        assertTrue(params.getBoolean("isShowNotification"))
        assertEquals("lyric_display", params.getString("business"))
        listOf("param_v2", "chatInfo", "baseInfo", "actions", "progressInfo").forEach {
            assertFalse("不能同时提供另一套展开模板：$it", params.has(it))
        }
        assertFalse(params.getBoolean("filterWhenNoPermission"))
        assertTrue(params.getBoolean("updatable"))
        assertEquals("reopen", params.getString("reopen"))
        assertFalse(params.getBoolean("enableFloat"))
        assertFalse(params.getBoolean("islandFirstFloat"))
    }

    @Test fun compactIslandKeepsCoverAndBoundedMetadataOnLeftAndLyricOnRight() {
        val island = params().getJSONObject("param_island")
        val big = island.getJSONObject("bigIslandArea")
        val left = big.getJSONObject("imageTextInfoLeft")
        assertEquals(summary.title, left.getJSONObject("textInfo").getString("title"))
        assertEquals(summary.artist, left.getJSONObject("textInfo").getString("content"))
        assertEquals(HYPER_OS_COVER_KEY, left.getJSONObject("picInfo").getString("pic"))
        assertEquals(summary.lyric, big.getJSONObject("textInfo").getString("title"))
        assertFalse(big.has("actions"))
        assertEquals(HYPER_OS_COVER_KEY, island.getJSONObject("smallIslandArea")
            .getJSONObject("picInfo").getString("pic"))
    }

    @Test fun missingArtistDoesNotCreateAnEmptyAuxiliarySlot() {
        val left = params(summary.copy(artist = "")).getJSONObject("param_island")
            .getJSONObject("bigIslandArea").getJSONObject("imageTextInfoLeft").getJSONObject("textInfo")
        assertFalse(left.has("content"))
    }

    @Test fun aodAndTickerCannotReintroduceTheUnboundedLyric() {
        val params = params()
        assertEquals(summary.lyric, params.getString("aodTitle"))
        assertEquals(summary.lyric, params.getString("ticker"))
        assertEquals(HYPER_OS_COVER_KEY, params.getString("aodPic"))
    }

    @Test fun newTrackIsPromotedButLyricAndProgressRefreshesDoNotRepeatedlyRequestPromotion() {
        assertTrue(params().getJSONObject("param_island").getBoolean("islandOrder"))
        val refresh = params(sequence = 2, bringToFront = false)
        assertFalse(refresh.getJSONObject("param_island").getBoolean("islandOrder"))
        assertEquals(2L, refresh.getLong("sequence"))
        assertNotEquals(params().toString(), refresh.toString())
    }

    @Test fun noLyricsIsExplicitInCompactAndAmbientText() {
        val params = params(summary.copy(lyric = HYPER_OS_NO_LYRICS))
        assertEquals("暂无歌词", params.getString("ticker"))
        assertEquals("暂无歌词", params.getString("aodTitle"))
        assertEquals("暂无歌词", params.getJSONObject("param_island").getJSONObject("bigIslandArea")
            .getJSONObject("textInfo").getString("title"))
    }
}
