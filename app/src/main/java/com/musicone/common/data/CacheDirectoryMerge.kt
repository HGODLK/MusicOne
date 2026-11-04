package com.musicone

/** 刷新目录时一次建立索引，避免大量歌单逐项扫描旧目录。 */
internal fun mergeCacheDirectoryTracks(fresh: List<MusicPlaylist>, previous: List<MusicPlaylist>): List<MusicPlaylist> {
    val byKey = buildMap { previous.forEach { playlist -> putIfAbsent(cachePlaylistKey(playlist), playlist) } }
    return fresh.map { playlist ->
        val old = byKey[cachePlaylistKey(playlist)]
        if (old == null) playlist else playlist.copy(
            tracks = if (playlist.tracks.size < playlist.count) old.tracks else playlist.tracks,
            artworkUrl = playlist.artworkUrl?.takeIf(String::isNotBlank) ?: old.artworkUrl,
        )
    }
}
