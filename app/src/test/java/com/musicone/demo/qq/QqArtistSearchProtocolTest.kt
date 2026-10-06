package com.musicone.demo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QqArtistSearchProtocolTest {
    @Test fun globalSingerResultKeepsMidAndNumericIdentity() {
        val data = JSONObject("""{
            "body":{"singer":{"list":[{
              "singerMID":"artist-mid","singerID":4558,"singerName":"歌手"
            }]}},"meta":{"nextpage":-1}
        }""")

        val singer = data.parseQqSearchPage(QqSearchTab.SINGER, 1, false).singers.single()

        assertEquals("artist-mid", singer.id)
        assertEquals(4558L, singer.numericId)
    }

    @Test fun requestUsesSingerPageScopeAndLocalSongFilter() {
        val context = qqArtistSearchCustomInfo(
            singerId = 4558,
            tabId = "song",
            count = 320,
            sort = ArtistSongSort.Popular,
            excludedSongIds = listOf("97773", "无效", "97773", "0"),
        )
        val request = qqArtistSongSearchRequest(" 晴天 ", 0, context)
        val params = request.getJSONObject("param")

        assertEquals("music.adaptor.searchSvr", request.getString("module"))
        assertEquals("DoSearch", request.getString("method"))
        assertEquals(7, params.getInt("search_source"))
        assertEquals(2, params.getInt("search_type"))
        assertEquals("晴天", params.getString("searchkey"))
        assertEquals("search.android.keyboard.singersonglist", params.getString("remoteplace"))
        assertEquals("4558", context.getString("SingerID"))
        assertEquals("320", context.getString("Count"))
        assertEquals("1", context.getString("SortType"))
        assertEquals("97773", context.getString("filter_song"))
    }

    @Test fun responseKeepsOnlyCurrentSingerAndUsesServerCursor() {
        val response = JSONObject("""{
            "ret_code":0,"estimate_num":12,"next_offset":20,
            "custom_info":{"SingerID":"4558","TabID":"song"},
            "data":{"track_items":[
              {"id":1,"mid":"song-a","name":"歌曲甲","interval":180,
               "singer":[{"mid":"artist-current","name":"歌手甲"}]},
              {"id":2,"mid":"song-b","name":"同名翻唱","interval":200,
               "singer":[{"mid":"artist-other","name":"歌手乙"}]}
            ]}
        }""")

        val page = response.parseQqArtistSearchPage("artist-current", false)

        assertEquals(listOf("qq-song-a"), page.items.map { it.id })
        assertEquals(20, page.nextOffset)
        assertEquals(12, page.total)
        assertEquals("4558", page.customInfo.getString("SingerID"))
    }

    @Test fun terminalResponseHasNoCursorAndEmptyResultIsValid() {
        val page = JSONObject("""{
            "ret_code":0,"estimate_num":0,"next_offset":-1,
            "custom_info":{},"data":{"track_items":[]}
        }""").parseQqArtistSearchPage("artist-current", false)

        assertTrue(page.items.isEmpty())
        assertNull(page.nextOffset)
        assertFalse(page.customInfo.keys().hasNext())
    }
}
