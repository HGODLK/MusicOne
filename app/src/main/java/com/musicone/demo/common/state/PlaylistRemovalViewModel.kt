package com.musicone.demo

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal class PlaylistRemovalBatch(val playlist: MusicPlaylist, val session: PlatformSession) {
    val pending = DeferredRemovals<MusicTrack>(MusicTrack::id)
    var writing by mutableStateOf(emptySet<String>())
    var flushAgain = false
    val ids: Set<String> get() = pending.ids + writing
    fun set(track: MusicTrack, remove: Boolean) {
        if (track.id in writing) return
        if (remove) pending.stage(track) else pending.undo(track.id)
    }
}

internal data class PlaylistRemovalActions(
    val playlistId: String = "",
    val ids: Set<String> = emptySet(),
    val set: (MusicTrack, Boolean) -> Unit = { _, _ -> },
)
internal val LocalPlaylistRemovals = staticCompositionLocalOf { PlaylistRemovalActions() }

/** 写入由页面之外的生命周期承接，菜单关闭和多选退出均不会提交。 */
internal class PlaylistRemovalViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = PlatformPreferences(application)
    private val library = MusicLibraryStore.get(application)
    private val writer = PlaylistSongWriter(preferences, library)
    private val batches = mutableMapOf<Pair<String, String>, PlaylistRemovalBatch>()

    fun batch(playlist: MusicPlaylist): PlaylistRemovalBatch {
        val session = preferences.readSession(playlist.source)
        val credential = if (playlist.isManagedLibrary) "" else session.credential
        return batches.getOrPut(playlist.id to credential) { PlaylistRemovalBatch(playlist, session) }
    }

    fun flush(batch: PlaylistRemovalBatch, onChanged: (MusicPlaylist) -> Unit, onError: (String) -> Unit) {
        if (batch.writing.isNotEmpty()) { batch.flushAgain = true; return }
        viewModelScope.launch {
            val writeAll: (suspend (List<MusicTrack>) -> Unit)? =
                if (batch.playlist.source == MusicSource.QQ && !batch.playlist.isManagedLibrary)
                    { songs -> writer.changeAll(batch.playlist, songs, false, batch.session) } else null
            val result = commitPlaylistRemovals(batch, writeAll) { song -> writer.change(batch.playlist, song, false, batch.session) }
            result.error?.let(onError)
            if (result.completed.isNotEmpty()) onChanged(batch.playlist)
            if (batch.flushAgain) {
                batch.flushAgain = false
                flush(batch, onChanged, onError)
            }
        }
    }
}

internal data class PlaylistRemovalResult(val completed: Set<String>, val error: String? = null)

internal suspend fun commitPlaylistRemovals(batch: PlaylistRemovalBatch,
    writeAll: (suspend (List<MusicTrack>) -> Unit)? = null,
    write: suspend (MusicTrack) -> Unit): PlaylistRemovalResult {
    if (batch.writing.isNotEmpty()) return PlaylistRemovalResult(emptySet())
    val songs = batch.pending.drain()
    batch.writing = songs.mapTo(hashSetOf(), MusicTrack::id)
    val completed = linkedSetOf<String>()
    try {
        if (writeAll != null && songs.isNotEmpty()) {
            try { writeAll(songs); completed += songs.map(MusicTrack::id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { return PlaylistRemovalResult(completed, "移除失败，歌曲已保留：${error.asUserMessage()}") }
            return PlaylistRemovalResult(completed)
        }
        for (song in songs) {
            try { write(song); completed += song.id }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                return PlaylistRemovalResult(completed, "已移除 ${completed.size} 首，其余保留：${error.asUserMessage()}")
            }
        }
        return PlaylistRemovalResult(completed)
    } finally { batch.writing = emptySet() }
}
