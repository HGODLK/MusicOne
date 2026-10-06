package com.musicone.demo

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QqSearchProtocolTest {
    @Test fun desktopRequestUsesOfficialCategoriesAndPagination() {
        QqSearchTab.entries.drop(1).forEach { tab ->
            val request = qqSearchRequest("  关键词  ", tab, 2)
            assertEquals("DoSearchForQQMusicDesktop", request.getString("method"))
            val param = request.getJSONObject("param")
            assertEquals(tab.type, param.getInt("search_type"))
            assertEquals("关键词", param.getString("query"))
            assertEquals(2, param.getInt("page_num"))
            assertEquals(30, param.getInt("sin"))
        }
    }

    private fun response(field: String, item: String) = JSONObject("""
        {"meta":{"nextpage":2,"is_filter":0},"body":{"$field":{"list":[$item]}}}
    """)

    @Test fun parsesDesktopSongListAndKeepsPlaybackIdentity() {
        val data = response("song", """{"id":123,"mid":"song-mid","name":"歌曲甲",
            "singer":[{"name":"歌手甲"}],"album":{"mid":"album-mid","name":"专辑甲"},"interval":200}""")
        val page = data.parseQqSearchPage(QqSearchTab.SONG, 1, false)
        assertEquals("qq-song-mid", page.songs.single().id)
        assertEquals("歌手甲", page.songs.single().artists)
        assertTrue(page.hasMore)
    }

    @Test fun parsesDesktopSingerAlbumAndPlaylistFields() {
        val singer = response("singer", """{"singerMID":"singer-mid","singerName":"歌手甲",
            "singerPic":"http://example.com/singer.jpg"}""")
            .parseQqSearchPage(QqSearchTab.SINGER, 1, false).singers.single()
        assertEquals("https://example.com/singer.jpg", singer.artwork)
        val album = response("album", """{"albumMID":"album-mid","albumName":"专辑甲",
            "albumPic":"http://example.com/album.jpg","singerName":"歌手甲","song_count":12,"publicTime":"2020-01-01"}""")
            .parseQqSearchPage(QqSearchTab.ALBUM, 1, false).albums.single()
        assertEquals("qq-album-album-mid", album.id)
        assertEquals(12, album.count)
        assertEquals("https://example.com/album.jpg", album.artworkUrl)
        assertEquals("2020-01-01", album.description)
        val playlist = response("songlist", """{"dissid":"123","dissname":"歌单甲",
            "imgurl":"http://example.com/list.jpg","creator":{"name":"创建者"},"song_count":8}""")
            .parseQqSearchPage(QqSearchTab.PLAYLIST, 1, false).playlists.single()
        assertEquals("qq-123", playlist.id)
        assertEquals("创建者", playlist.subtitle)
        assertEquals(8, playlist.count)
        assertEquals("https://example.com/list.jpg", playlist.artworkUrl)
    }

    @Test fun filteredResponseIsNotReportedAsEmptyOrLoginFailure() {
        val error = runCatching {
            JSONObject("""{"code":0,"SONG":{"code":0,"data":{"meta":{"is_filter":-2}}}}""")
                .qqSearchData("SONG")
        }.exceptionOrNull()
        assertTrue(error is PlatformApiException)
        assertFalse(error!!.message.orEmpty().contains("登录"))
    }

    @Test fun searchBorrowsSameArtistCoverWithoutReplacingTrackIdentity() {
        val data = response("song", """{"id":123,"mid":"short-mid","name":"歌曲甲（片段）",
            "singer":[{"name":"DOUDOU·"}],"interval":20}""")
        val original = data.parseQqSearchPage(QqSearchTab.SONG, 1, false).songs.single()
        val otherArtist = original.copy(id = "qq-other", title = "歌曲甲", artists = "另一位歌手",
            artworkUrl = "https://example.com/wrong.jpg")
        val full = original.copy(id = "qq-full", title = "歌曲甲", artists = "DOUDOU", album = "专辑甲",
            artworkUrl = "https://example.com/correct.jpg", durationMs = 200000)
        val resolved = resolveQqSearchArtwork(listOf(original, otherArtist, full)).first()
        assertEquals(full.artworkUrl, resolved.artworkUrl)
        assertEquals(original.id, resolved.id)
        assertEquals(original.durationMs, resolved.durationMs)
        assertEquals(original.catalogId, resolved.catalogId)
        assertEquals(original.artists, resolved.artists)
    }
}
