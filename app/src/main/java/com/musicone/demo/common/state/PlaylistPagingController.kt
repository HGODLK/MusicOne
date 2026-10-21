package com.musicone.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class PlaylistPagingEntry(playlist: MusicPlaylist) {
    var page by mutableStateOf(if (playlist.isManagedLibrary || !shouldCachePlaylistDetail(playlist))
        PlaylistTrackPage(playlist) else null)
        internal set
    var loading by mutableStateOf(false); internal set
    var error by mutableStateOf<String?>(null); internal set
    internal val mutex = Mutex()

    fun remove(trackId: String) {
        page = page?.let { it.copy(playlist = it.playlist.copy(
            tracks = it.playlist.tracks.filterNot { track -> track.id == trackId },
            count = (it.playlist.count - 1).coerceAtLeast(0))) }
    }
}

/** 浏览只推进一页；完整操作复用同一游标与互斥锁，不并发重复拉取。 */
internal class PlaylistPagingController(
    private val fetch: suspend (MusicPlaylist, PlaylistTrackCursor?) -> PlaylistTrackPage,
) {
    private val entries = mutableMapOf<String, PlaylistPagingEntry>()
    fun entry(playlist: MusicPlaylist): PlaylistPagingEntry {
        val result = entries.getOrPut(playlist.id) { PlaylistPagingEntry(playlist) }
        if (playlist.isManagedLibrary || !shouldCachePlaylistDetail(playlist)) {
            if (result.page?.playlist != playlist) result.page = PlaylistTrackPage(playlist)
        }
        return result
    }
    fun clear() { entries.clear() }
    fun invalidate(id: String) { entries.remove(id) }
    fun release(id: String, expected: PlaylistPagingEntry) {
        if (entries[id] === expected) entries.remove(id)
    }

    suspend fun first(playlist: MusicPlaylist): MusicPlaylist = advance(playlist, first = true)
    suspend fun more(playlist: MusicPlaylist): MusicPlaylist = advance(playlist, first = false)

    suspend fun complete(playlist: MusicPlaylist): MusicPlaylist {
        val target = entry(playlist)
        var result = first(playlist)
        while (target.page?.next != null) {
            currentCoroutineContext().ensureActive()
            checkCurrent(playlist, target)
            result = more(result)
        }
        return result
    }

    fun checkCurrent(playlist: MusicPlaylist, target: PlaylistPagingEntry) {
        if (entries[playlist.id] !== target) throw CancellationException("歌单会话已更新")
    }

    private suspend fun advance(playlist: MusicPlaylist, first: Boolean): MusicPlaylist {
        val target = entry(playlist)
        return target.mutex.withLock {
            checkCurrent(playlist, target)
            val previous = target.page
            if (first && previous != null || !first && previous != null && previous.next == null) {
                return@withLock requireNotNull(previous).playlist
            }
            target.loading = true
            target.error = null
            try {
                val incoming = fetch(previous?.playlist ?: playlist, previous?.next)
                currentCoroutineContext().ensureActive()
                checkCurrent(playlist, target)
                val merged = withContext(Dispatchers.Default) { mergePlaylistTrackPage(previous, incoming) }
                currentCoroutineContext().ensureActive()
                checkCurrent(playlist, target)
                target.page = merged
                merged.playlist
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                target.error = error.asUserMessage()
                throw error
            } finally {
                target.loading = false
            }
        }
    }
}
