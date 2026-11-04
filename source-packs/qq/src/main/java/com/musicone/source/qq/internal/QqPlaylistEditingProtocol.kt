package com.musicone.source.qq.internal

import org.json.JSONObject

internal enum class PlaylistEditMode { Actions, Create, Rename, Delete, RefreshSubscription, StopSubscription }

internal fun qqPlaylistEditRequest(cookie: String, action: PlaylistEditMode, playlist: MusicPlaylist?, name: String): JSONObject {
    val comm = qqPlaybackComm(cookie)
    require(comm.optString("authst").isNotBlank()) { "请先登录 QQ 音乐" }
    val param = JSONObject().put("bFmtUtf8", true)
    val method = when (action) {
        PlaylistEditMode.Create -> { param.put("dirName", name).put("dirShow", 1); "AddPlaylist" }
        PlaylistEditMode.Rename, PlaylistEditMode.Delete -> {
            val directory = playlist?.qqDirectoryId ?: throw PlatformApiException("缺少歌单目录信息，请刷新后重试")
            require(directory > 0 && !playlist.isQqFavoritesShortcut()) { "不能编辑这个歌单" }
            param.put("dirId", directory)
            if (action == PlaylistEditMode.Rename) { param.put("mask", 1).put("dirNewName", name); "EditPlaylist" }
            else { param.put("dirName", playlist.title); "DelPlaylist" }
        }
        else -> error("请选择操作")
    }
    return JSONObject().put("comm", comm).put("req_0", JSONObject().put("module", "music.musicasset.PlaylistBaseWrite")
        .put("method", method).put("param", param))
}
