package com.musicone.demo

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player

internal data class LivePlaybackRestore(
    val state: MusicOneUiState,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val playing: Boolean,
)

/** 会话持有播放器连接、进度归属和持久化，页面重建时优先接管仍在播放的服务。 */
internal class PlaybackSessionCoordinator(private val context: Context) {
    private val connection = SystemPlaybackConnection(context)
    private val positionOwner = PlaybackPositionOwner()
    private val persistence = QqPlaybackPersistence(context)
    val player get() = connection.player

    fun whenReady(action: (Player) -> Unit) = connection.whenReady(action)
    fun restoreStored(source: MusicSource, state: MusicOneUiState) = persistence.restore(source, state)
    fun restoreLive(source: MusicSource, state: MusicOneUiState, generation: Long, player: Player): LivePlaybackRestore? {
        val restored = retainedPlaybackSession.restore(source, player.currentMediaItem?.mediaId, state, player.isPlaying)
            ?: return null
        positionOwner.attach(generation, requireNotNull(restored.currentTrack).id)
        return LivePlaybackRestore(restored, player.currentPosition.coerceAtLeast(0L), player.playWhenReady, player.isPlaying)
    }

    fun matches(generation: Long, trackId: String?) =
        positionOwner.matches(generation, trackId, player?.currentMediaItem?.mediaId)
    fun position(generation: Long, trackId: String?, saved: Long) =
        positionOwner.position(generation, trackId, player?.currentMediaItem?.mediaId, player?.currentPosition, saved)
    fun remember(state: MusicOneUiState, playerId: String? = player?.currentMediaItem?.mediaId) =
        retainedPlaybackSession.remember(state, playerId)
    fun save(state: MusicOneUiState, position: Long) = persistence.save(state, position)
    fun saveProgressIfNeeded(state: MusicOneUiState, position: Long) = persistence.saveProgressIfNeeded(state, position)
    fun resetProgressBucket(position: Long) = persistence.resetProgressBucket(position)

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun start(track: MusicTrack, key: PlaybackRequestKey, positionMs: Long, playWhenReady: Boolean,
              isCurrent: (PlaybackRequestKey) -> Boolean, started: (Player) -> Unit, failed: () -> Unit) {
        whenReady { player ->
            if (!isCurrent(key)) return@whenReady
            runCatching {
                val metadata = MediaMetadata.Builder()
                    .setExtras(android.os.Bundle().apply { putLong("musicone_duration", track.durationMs) })
                    .setTitle(track.title).setArtist(track.artists).setAlbumTitle(track.album)
                    .apply { track.artworkUrl?.takeIf(String::isNotBlank)?.let { setArtworkUri(Uri.parse(it)) } }
                    .build()
                playbackTrace("MusicOne:audio-set-item") {
                    player.setMediaItem(MediaItem.Builder().setMediaId(track.id)
                        .setCustomCacheKey(playbackCacheKey(context, track).also { MusicDiskCache.available()?.activeKey = it })
                        .setUri(track.previewUrl).setMediaMetadata(metadata).build(), positionMs.coerceAtLeast(0L))
                }
                playbackTrace("MusicOne:audio-prepare") { player.prepare() }
                positionOwner.attach(key.generation, track.id)
                started(player)
                if (playWhenReady) playbackTrace("MusicOne:audio-play") { player.play() }
            }.onFailure { if (isCurrent(key)) failed() }
        }
    }

    fun close(state: MusicOneUiState, position: Long, listener: Player.Listener) {
        persistence.save(state, position)
        persistence.close()
        player?.removeListener(listener)
        connection.release()
    }
}
