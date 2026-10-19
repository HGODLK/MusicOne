package com.musicone.demo

import android.content.Context
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheWriter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 每个目标持有自己的写入器；B站等平台沿用解析结果中的请求头和缓存键。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackAudioPrefetch(private val context: Context) {
    @Volatile private var writer: CacheWriter? = null
    fun cancel() { writer?.cancel() }

    suspend fun cache(resolved: ResolvedPlayback) {
        if (offlinePlaybackKey(resolved.source.url) != null) return
        val disk = MusicDiskCache.get(context)
        if (disk.blockReason(resolved.track) != null) return
        val upstream = DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory()
            .setUserAgent(PlatformHttp.DEFAULT_USER_AGENT).setConnectTimeoutMs(5_000).setReadTimeoutMs(5_000))
        val coroutine = currentCoroutineContext()
        val source = disk.streamingFactory(upstream, strict = true).createDataSource()
        val data = DataSpec.Builder().setUri(cacheDownloadUrl(resolved.source))
            .setKey(playbackCacheKey(context, resolved.track))
            .setHttpRequestHeaders(resolved.source.requestHeaders).setPosition(0)
            .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
            .setLength(playbackPreloadBytes(resolved.source.bitRate, resolved.track.durationMs)).build()
        val activeWriter = CacheWriter(source, data, null) { _, _, _ -> coroutine.ensureActive() }
        writer = activeWriter
        try {
            coroutine.ensureActive()
            activeWriter.cache()
        } finally {
            writer = null
            disk.audioChanged()
        }
    }
}
