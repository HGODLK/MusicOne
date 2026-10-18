package com.musicone.demo

import org.json.JSONObject

internal class NeteasePlaylistPaging(
    private val detail: (String, String) -> JSONObject,
    private val songs: (List<String>, String, Boolean) -> List<MusicTrack>,
) {
    fun page(fallback: MusicPlaylist, cookie: String, vip: Boolean, cursor: PlaylistTrackCursor?): PlaylistTrackPage {
        val offset = cursor?.offset ?: 0
        val response = if (cursor?.trackIds == null) {
            detail("https://music.163.com/api/v6/playlist/detail?id=${fallback.remoteId().formEncoded()}&n=$PLAYLIST_TRACK_PAGE_SIZE&s=8", cookie)
                .also { it.requireSuccess("歌单加载失败") }
        } else null
        val shell = response?.parsePlaylistShell()
        val playlist = shell?.first?.copy(isOwned = fallback.isOwned) ?: fallback
        val ids = cursor?.trackIds ?: shell?.second.orEmpty()
        val pageIds = ids.drop(offset).take(PLAYLIST_TRACK_PAGE_SIZE)
        val embedded = response?.parsePlaylistEmbeddedTracks(vip).orEmpty()
        val missing = missingPlaylistTrackIds(pageIds, embedded)
        val tracks = orderedPlaylistTracks(pageIds, embedded + songs(missing, cookie, vip))
        val end = offset + pageIds.size
        return PlaylistTrackPage(playlist.copy(tracks = tracks),
            if (end < ids.size) PlaylistTrackCursor(end, ids) else null)
    }
}
