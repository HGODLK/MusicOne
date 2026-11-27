package com.musicone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 离线时直接用界面上已有的歌单快照播放，不要先去拉远程分页。 */
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

/** 点播单首时如果补页失败，允许先播已加载的部分；但“播放全部”和“全选”仍要求拉完整。 */
internal suspend fun completePlaylistForSelectedTrack(
    snapshot: () -> MusicPlaylist,
    selected: MusicTrack?,
    complete: suspend () -> MusicPlaylist,
): MusicPlaylist = try {
    complete()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    currentCoroutineContext().ensureActive()
    val loaded = snapshot()
    if (selected == null || loaded.tracks.none { it.id == selected.id && it.source == selected.source }) throw error
    loaded
}
