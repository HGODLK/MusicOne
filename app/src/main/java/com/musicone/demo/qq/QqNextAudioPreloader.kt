package com.musicone.demo

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import kotlinx.coroutines.*

/** 音频只准备下一首开头，歌词另用五首窗口；观察播放器在主线程，网络与写入在后台。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class QqNextAudioPreloader(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private data class Request(val currentId: String, val trackId: String, val namespace: String, val quality: AudioQuality)
    private data class Ready(val request: Request, val playback: ResolvedPlayback, val createdMs: Long)
    private val resolver = PlatformPlaybackResolver(context, qqRequestOrigin = QqRequestOrigin.PRELOAD)
    private val lyricsPreloader = QqNextLyricsPreloader(context)
    private val lyricsWindow = QqLyricsPreloadWindow(scope, lyricsPreloader::preload)
    private val preferences = PlatformPreferences(context)
    private val qualities = PlaybackQualityPreferences(context)
    private var request: Request? = null
    private var job: Job? = null
    @Volatile private var writer: CacheWriter? = null
    @Volatile private var ready: Ready? = null

    fun observe(state: () -> MusicOneUiState, switching: () -> Boolean, player: () -> Player?) {
        scope.launch {
            while (isActive) {
                val snapshot = state()
                val transport = player()
                val target = nextQqPreloadTrack(snapshot)
                val current = snapshot.currentTrack
                val canLoad = !switching() && target != null && transport != null &&
                    transport.currentMediaItem?.mediaId == current?.id && canPreloadQqAudio(
                        transport.isPlaying,
                        transport.totalBufferedDuration,
                        (transport.duration.takeIf { it > 0 } ?: current?.durationMs ?: 0) - transport.currentPosition,
                    )
                if (!canLoad) stop() else {
                    val quality = PlaybackOptions.effective(context, qualities.read(MusicSource.QQ)).playbackEquivalent()
                    val next = Request(requireNotNull(current).id, requireNotNull(target).id,
                        preferences.readSession(MusicSource.QQ).cacheNamespace(), quality)
                    if (request != next) start(next, target)
                    lyricsWindow.update(nextQqLyricsPreloadTracks(snapshot), next.namespace)
                }
                delay(1_000)
            }
        }
    }

    private fun start(next: Request, target: MusicTrack) {
        val previousJob = job
        stop()
        request = next
        job = scope.launch(Dispatchers.IO) {
            previousJob?.join()
            // 等当前起播和附属信息更新稳定，不在刚切歌时争抢带宽与磁盘。
            delay(3_000)
            try {
                val started = nowMs()
                val resolved = resolver.resolvePreferred(target, next.quality)
                ensureActive()
                if (resolved.source.trial ||
                    preferences.readSession(MusicSource.QQ).cacheNamespace() != next.namespace) return@launch
                val key = playbackCacheKey(context, resolved.track)
                if (offlinePlaybackKey(resolved.source.url) == null) {
                    val disk = MusicDiskCache.get(context)
                    val upstream = DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory()
                        .setUserAgent(PlatformHttp.DEFAULT_USER_AGENT)
                        .setConnectTimeoutMs(5_000).setReadTimeoutMs(5_000))
                    val dataSource = CacheDataSource.Factory().setCache(disk.audio)
                        .setUpstreamDataSourceFactory(upstream).createDataSource()
                    val currentWriter = CacheWriter(dataSource, DataSpec.Builder()
                        .setUri(resolved.source.url).setKey(key).setPosition(0)
                        .setLength(qqAudioPreloadBytes(resolved.source.bitRate)).build(), null) { _, _, _ ->
                        ensureActive()
                    }
                    writer = currentWriter
                    try {
                        ensureActive()
                        currentWriter.cache()
                    } finally {
                        if (writer === currentWriter) writer = null
                    }
                    disk.audioChanged()
                }
                ensureActive()
                if (preferences.readSession(MusicSource.QQ).cacheNamespace() == next.namespace) {
                    ready = Ready(next, resolved, started)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 预取失败不打断当前播放；真正切歌时仍走正常解析与错误提示。
            }
        }
    }

    /** 切歌开始立即撤销后台写入，已完成的数据仍由正常音频缓存管理。 */
    fun stop() {
        lyricsWindow.stop()
        writer?.cancel()
        job?.cancel()
        request = null
    }

    fun take(track: MusicTrack, quality: AudioQuality): ResolvedPlayback? {
        val saved = ready ?: return null
        if (track.source != MusicSource.QQ || saved.request.trackId != track.id ||
            saved.request.quality != quality ||
            saved.request.namespace != preferences.readSession(MusicSource.QQ).cacheNamespace() ||
            !qqPreloadedSourceFresh(saved.createdMs, nowMs())) return null
        ready = null
        // 元数据以当前队列为准，预取仅交付音源，不能覆盖最新歌词和收藏状态。
        return saved.playback.copy(track = track.copy(previewUrl = saved.playback.source.url))
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L
}
