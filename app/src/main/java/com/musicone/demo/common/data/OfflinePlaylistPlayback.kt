package com.musicone.demo

/** 离线使用已展示的歌单快照，不能先补齐网络分页再读取本地音频。 */
internal suspend fun playlistForPlayback(
    snapshot: MusicPlaylist,
    online: Boolean,
    cachedTrackIds: suspend () -> Set<String>,
    complete: suspend () -> MusicPlaylist,
): MusicPlaylist {
    if (online) return complete()
    val cached = cachedTrackIds()
    val tracks = snapshot.tracks.filter {
        if (it.source == MusicSource.LOCAL) it.playable else it.id in cached
    }
    require(tracks.isNotEmpty()) { "歌单中没有已完整缓存的歌曲" }
    return snapshot.copy(tracks = tracks, count = tracks.size)
}
