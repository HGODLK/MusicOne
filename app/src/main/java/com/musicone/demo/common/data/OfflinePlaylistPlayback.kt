package com.musicone.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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

/** 补页失败时仍允许点播已加载歌曲；全单播放与全选继续要求完整结果。 */
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
