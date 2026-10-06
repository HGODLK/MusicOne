package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class QqPlaylistSongRequestTest {
    private val cookie = "uin=123456; qqmusic_key=test-key;"
    private val track = MusicTrack("qq-test", MusicSource.QQ, "歌曲", "歌手", "", 1000,
        0L, 0L, "", "", catalogId = "456", providerType = 0)
    private val playlist = MusicPlaylist("qq-987654", MusicSource.QQ, "自建歌单", "", "", 1,
        0L, 0L, "", listOf(track), qqDirectoryId = 7L)

    @Test fun addAndRemoveUseBothOfficialIdentifiers() {
        for (add in listOf(true, false)) {
            val request = qqPlaylistSongRequest(cookie, playlist, track, add).getJSONObject("req_0")
            assertEquals(if (add) "AddSonglist" else "DelSonglist", request.getString("method"))
            val param = request.getJSONObject("param")
            assertEquals(7L, param.getLong("dirId"))
            assertEquals(987654L, param.getLong("tid"))
            assertEquals(456L, param.getJSONArray("v_songInfo").getJSONObject(0).getLong("songId"))
        }
    }

    @Test fun publicIdCannotAccidentallyBecomeDirectoryId() {
        try {
            qqPlaylistSongRequest(cookie, playlist.copy(qqDirectoryId = null), track, false)
            fail("目录缺失时不可写入")
        } catch (_: PlatformApiException) { }
    }
}
