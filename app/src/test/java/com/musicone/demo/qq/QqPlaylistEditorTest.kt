package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class QqPlaylistEditorTest {
    private val cookie = "uin=123456; qqmusic_key=test-key"
    private val playlist = MusicPlaylist("qq-9999", MusicSource.QQ, "原名", "", "", 0,
        0L, 0L, "", emptyList(), qqDirectoryId = 301)

    @Test fun renameUsesDirectoryNotPublicPlaylistIdAndOnlyChangesName() {
        val request = qqPlaylistEditRequest(cookie, PlaylistEditMode.Rename, playlist, "新名称").getJSONObject("req_0")
        assertEquals("EditPlaylist", request.getString("method"))
        val param = request.getJSONObject("param")
        assertEquals(301, param.getInt("dirId"))
        assertEquals(1, param.getInt("mask"))
        assertEquals("新名称", param.getString("dirNewName"))
        assertFalse(param.has("dirNewDesc"))
    }
    @Test fun createAndDeleteHaveSeparatePayloads() {
        val create = qqPlaylistEditRequest(cookie, PlaylistEditMode.Create, null, "新歌单").getJSONObject("req_0")
        assertEquals("AddPlaylist", create.getString("method"))
        assertFalse(create.getJSONObject("param").has("dirId"))
        val delete = qqPlaylistEditRequest(cookie, PlaylistEditMode.Delete, playlist, "").getJSONObject("req_0")
        assertEquals("DelPlaylist", delete.getString("method"))
        assertEquals("原名", delete.getJSONObject("param").getString("dirName"))
    }
    @Test(expected = IllegalArgumentException::class) fun favoritesCannotBeDeleted() {
        qqPlaylistEditRequest(cookie, PlaylistEditMode.Delete, playlist.copy(id = QQ_FAVORITES_PLAYLIST_ID), "")
    }
}
