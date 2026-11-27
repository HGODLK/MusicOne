package com.musicone

/** 刷新歌单目录时先建 Map 索引合并旧歌曲，避免大量歌单嵌套遍历。 */
internal fun mergeCacheDirectoryTracks(fresh: List<MusicPlaylist>, previous: List<MusicPlaylist>): List<MusicPlaylist> {
    val byKey = buildMap<String, MusicPlaylist> { previous.forEach { playlist -> getOrPut(cachePlaylistKey(playlist)) { playlist } } }
    return fresh.map { playlist ->
        val old = byKey[cachePlaylistKey(playlist)]
        if (old == null) playlist else playlist.copy(
            tracks = if (playlist.tracks.size < playlist.count) old.tracks else playlist.tracks,
            artworkUrl = playlist.artworkUrl?.takeIf(String::isNotBlank) ?: old.artworkUrl,
        )
    }
}
