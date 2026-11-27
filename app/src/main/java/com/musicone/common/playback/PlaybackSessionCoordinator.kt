package com.musicone

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

internal data class LivePlaybackRestore(
    val state: MusicOneUiState,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val playing: Boolean,
)

/** 管理播放器连接、进度归属和播放状态持久化，页面重建时直接接管后台服务。 */
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
                    // 正在自然过渡时拖进度，交给自然过渡控制器处理（选对歌曲并清掉另一首）
                    if (AutoMixEngine.controller?.seek(player, target) == true) return@let
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

    /** 自然过渡切歌后，播放器已经在播下一首了，这里只把进度归属和持久化状态切过去。 */
    fun adoptAutoMixHandoff(generation: Long, trackId: String, state: MusicOneUiState) {
        positionOwner.attach(generation, trackId)
        retainedPlaybackSession.remember(state, trackId)
    }

    fun matches(generation: Long, trackId: String?) =
        positionOwner.matches(generation, trackId, player?.currentMediaItem?.mediaId)
    fun position(generation: Long, trackId: String?, saved: Long): Long {
        val transport = player ?: return saved
        AutoMixEngine.controller?.position(trackId)?.let { return it }
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
            if (resolvedPlaybackRequiresDolby(track.previewUrl) && !DolbyPlaybackPolicy.eligibility.allowed) {
                failed()
                return@whenReady
            }
            runCatching {
                val startingPosition = pendingSeek.take(key) ?: positionMs.coerceAtLeast(0L)
                val metadata = systemMediaMetadata(track)
                playbackTrace("MusicOne:audio-set-item") {
                    val item = MediaItem.Builder().setMediaId(track.id).setUri(track.previewUrl).setMediaMetadata(metadata)
                    if (track.previewUrl.startsWith("data:application/dash+xml")) {
                        item.setMimeType("application/dash+xml")
                        MusicDiskCache.available()?.activeKey = SupplementalPlaybackRequests.cacheKey(track.previewUrl)
                    } else if (track.source != MusicSource.LOCAL) {
                        item.setCustomCacheKey(playbackCacheKey(context, track).also { MusicDiskCache.available()?.activeKey = it })
                    } else MusicDiskCache.available()?.activeKey = null
                    SupplementalPlaybackRequests.resource(track.previewUrl)?.mimeType?.let(item::setMimeType)
                    player.setMediaItem(item.build(), startingPosition)
                }
                playbackTrace("MusicOne:audio-prepare") { player.prepare() }
                PlaybackPipelineDiagnostics.stage("已请求准备", key.generation)
                positionOwner.attach(key.generation, track.id, startingPosition)
                started(player, startingPosition)
                // 服务连上或音频准备好时，用户可能早就点了暂停，所以这里重新读最新的播放意图。
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
