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

    suspend fun cache(resolved: ResolvedPlayback, mayContinue: () -> Boolean = { true }): Boolean {
        if (offlinePlaybackKey(resolved.source.url) != null) return true
        val disk = MusicDiskCache.get(context)
        if (disk.blockReason(resolved.track) != null) return false
        val upstream = DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("User-Agent" to PlatformHttp.DEFAULT_USER_AGENT))
            .setConnectTimeoutMs(5_000).setReadTimeoutMs(5_000))
        val coroutine = currentCoroutineContext()
        fun checkAllowed() {
            coroutine.ensureActive()
            if (!mayContinue()) throw kotlinx.coroutines.CancellationException("当前播放需要优先缓冲")
        }
        val source = disk.streamingFactory(upstream, strict = true).createDataSource()
        val data = DataSpec.Builder().setUri(cacheDownloadUrl(resolved.source))
            .setKey(playbackCacheKey(context, resolved.track))
            .setHttpRequestHeaders(resolved.source.requestHeaders).setPosition(0)
            .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
            .setLength(playbackPreloadBytes(resolved.source.bitRate, resolved.track.durationMs)).build()
        val activeWriter = CacheWriter(source, data, null) { _, _, _ -> checkAllowed() }
        writer = activeWriter
        try {
            checkAllowed()
            activeWriter.cache()
            return true
        } finally {
            writer = null
            disk.audioChanged()
        }
    }
}
