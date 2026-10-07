package com.musicone.demo

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class QqPlaylistSongMenuState(
    private val scope: CoroutineScope,
    private val preferences: PlatformPreferences,
    private val repository: QqPlaylistSongRepository = QqPlaylistSongRepository(),
) {
    var track by mutableStateOf<MusicTrack?>(null)
        private set
    var anchor by mutableStateOf(Rect.Zero)
        private set
    var expanded by mutableStateOf(false)
        private set
    var choosing by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var targets by mutableStateOf(emptyList<MusicPlaylist>())
        private set
    var surfaceBounds = Rect.Zero
    var surfaceLayer: androidx.compose.ui.graphics.layer.GraphicsLayer? = null
    var backdropLayer: androidx.compose.ui.graphics.layer.GraphicsLayer? = null
    var backdropBounds = Rect.Zero
    var liveBackdrop = false
    var artistArtworkState: MenuArtistArtworkState? = null
    var choosingArtist by mutableStateOf(false)

    fun open(song: MusicTrack, bounds: Rect) {
        if (busy) return
        track = song
        anchor = bounds
        message = null
        choosing = false
        choosingArtist = false
        expanded = true
        if (song.artistRefs.isEmpty() || song.albumMid.isBlank()) launchRequest {
            val resolved = withContext(Dispatchers.IO) {
                QqApiClient().enrichTrackMetadata(song, preferences.readSession(MusicSource.QQ).credential)
            }
            if (track?.id == song.id) track = resolved
        }
    }

    fun dismiss() { if (!busy) expanded = false }
    fun back() { if (!busy) { if (choosingArtist) choosingArtist = false else if (choosing) choosing = false else dismiss() } }

    fun choose() {
        choosing = true
        launchRequest {
            targets = withContext(Dispatchers.IO) { repository.targets(preferences.readSession(MusicSource.QQ)) }
            if (targets.isEmpty()) message = "暂无可添加的自建歌单"
        }
    }

    fun change(playlistId: String, add: Boolean, onSuccess: (MusicPlaylist, MusicTrack) -> Unit) {
        val song = track ?: return
        launchRequest {
            val changed = withContext(Dispatchers.IO) {
                repository.change(preferences.readSession(MusicSource.QQ), playlistId, song, add)
            }
            onSuccess(changed, song)
            choosing = false
            if (add) message = "已添加到「${changed.title}」" else expanded = false
        }
    }

    private fun launchRequest(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.asUserMessage() }
            finally { busy = false }
        }
    }
}

internal val LocalQqPlaylistSongMenu = staticCompositionLocalOf<QqPlaylistSongMenuState?> { null }
