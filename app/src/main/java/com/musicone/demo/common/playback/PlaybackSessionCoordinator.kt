package com.musicone.demo

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

internal data class LivePlaybackRestore(
    val state: MusicOneUiState,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val playing: Boolean,
)

/** 会话持有播放器连接、进度归属和持久化，页面重建时优先接管仍在播放的服务。 */
internal class PlaybackSessionCoordinator(private val context: Context, requestConnection: () -> Unit = {}) {
    private val connection = ServicePlaybackConnection(requestConnection)
    private val positionOwner = PlaybackPositionOwner()
    private val pendingSeek = PlaybackPendingSeek()
    private val persistence = MixedPlaybackPersistence(context)
    val player get() = connection.player

    fun attach(player: Player) = connection.attach(player)
    fun detach(player: Player) = connection.detach(player)

    fun whenReady(action: (Player) -> Unit) = connection.whenReady(action)
    fun clearPendingSeek() = pendingSeek.clear()

    fun clearQueue(stillEmpty: () -> Boolean) {
        pendingSeek.clear()
        retainedPlaybackSession.clear()
        persistence.clearQueue()
        whenReady { player -> if (stillEmpty()) { player.stop(); player.clearMediaItems() } }
    }

    fun seek(key: PlaybackRequestKey, positionMs: Long, isCurrent: (PlaybackRequestKey) -> Boolean) {
        if (!isCurrent(key)) return
        pendingSeek.request(key, positionMs)
        whenReady { player ->
            if (isCurrent(key) && matches(key.generation, key.trackId)) {
                pendingSeek.take(key)?.let { target ->
                    positionOwner.awaitPosition(key.generation, key.trackId, target)
                    player.seekTo(target)
                }
            }
        }
    }
    fun restoreStored(source: MusicSource, state: MusicOneUiState) = persistence.restore(source, state)
    fun restoreLive(source: MusicSource, state: MusicOneUiState, generation: Long, player: Player): LivePlaybackRestore? {
        val restored = retainedPlaybackSession.restore(source, player.currentMediaItem?.mediaId, state, player.isPlaying)
            ?: return null
        positionOwner.attach(generation, requireNotNull(restored.currentTrack).id)
        return LivePlaybackRestore(restored, player.currentPosition.coerceAtLeast(0L), player.playWhenReady, player.isPlaying)
    }

    fun matches(generation: Long, trackId: String?) =
        positionOwner.matches(generation, trackId, player?.currentMediaItem?.mediaId)
    fun position(generation: Long, trackId: String?, saved: Long): Long {
        val transport = player ?: return saved
        return positionOwner.position(generation, trackId, transport.currentMediaItem?.mediaId,
            transport.currentPosition, saved, transport.playbackState == Player.STATE_READY,
            transport.duration, transport.playbackParameters.speed)
    }
    fun remember(state: MusicOneUiState, playerId: String? = player?.currentMediaItem?.mediaId) =
        retainedPlaybackSession.remember(state, playerId)
    fun save(state: MusicOneUiState, position: Long) = persistence.save(state, position)
    fun saveProgressIfNeeded(state: MusicOneUiState, position: Long) = persistence.saveProgressIfNeeded(state, position)
    fun resetProgressBucket(position: Long) = persistence.resetProgressBucket(position)

    fun refreshMetadata(track: MusicTrack, key: PlaybackRequestKey, isCurrent: (PlaybackRequestKey) -> Boolean) {
        val transport = player ?: return
        if (isCurrent(key) && matches(key.generation, key.trackId)) refreshSystemMediaMetadata(transport, track)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun start(track: MusicTrack, key: PlaybackRequestKey, positionMs: Long, playWhenReady: () -> Boolean,
              isCurrent: (PlaybackRequestKey) -> Boolean, started: (Player, Long) -> Unit, failed: () -> Unit) {
        whenReady { player ->
            if (!isCurrent(key)) return@whenReady
            if (qqPlaybackQuality(track.previewUrl.orEmpty()) == AudioQuality.DOLBY && !DolbyPlaybackPolicy.eligibility.allowed) {
                failed()
                return@whenReady
            }
            runCatching {
                val startingPosition = pendingSeek.take(key) ?: positionMs.coerceAtLeast(0L)
                val metadata = systemMediaMetadata(track)
                playbackTrace("MusicOne:audio-set-item") {
                    val item = MediaItem.Builder().setMediaId(track.id).setUri(track.previewUrl).setMediaMetadata(metadata)
                    if (track.source == MusicSource.BILIBILI && track.previewUrl.startsWith("data:application/dash+xml")) {
                        item.setMimeType("application/dash+xml")
                        MusicDiskCache.available()?.activeKey = SupplementalPlaybackRequests.cacheKey(track.previewUrl)
                    } else if (track.source != MusicSource.LOCAL) {
                        item.setCustomCacheKey(playbackCacheKey(context, track).also { MusicDiskCache.available()?.activeKey = it })
                    } else MusicDiskCache.available()?.activeKey = null
                    player.setMediaItem(item.build(), startingPosition)
                }
                playbackTrace("MusicOne:audio-prepare") { player.prepare() }
                PlaybackPipelineDiagnostics.stage("已请求准备", key.generation)
                positionOwner.attach(key.generation, track.id, startingPosition)
                started(player, startingPosition)
                // 服务连接和音源准备都可能晚于暂停操作，执行时读取最新意图。
                if (playWhenReady()) playbackTrace("MusicOne:audio-play") { player.play() }
                else player.pause()
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
