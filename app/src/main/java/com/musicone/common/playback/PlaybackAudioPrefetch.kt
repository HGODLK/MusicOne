package com.musicone

import android.content.Context
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheWriter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 负责把预加载的歌曲整首写入磁盘缓存（带上音源自带的请求头和缓存键）；
 * 中途被打断后重来会自动跳过已下好的部分。
 */
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
        try {
            checkAllowed()
            // 和手动下载共用同一套缓存逻辑：DASH 只下音轨，普通音频直接下到文件末尾。
            val data = cacheDownloadSpec(resolved.source, upstream, disk.audio,
                playbackCacheKey(context, resolved.track)) { write -> checkAllowed(); write() }
            val activeWriter = CacheWriter(source, data, null) { _, _, _ -> checkAllowed() }
            writer = activeWriter
            activeWriter.cache()
            return true
        } catch (quota: CacheQuotaException) {
            // 缓存空间满了就停，已经写进去的部分保留着，不再反复重试。
            return false
        } finally {
            writer = null
            disk.audioChanged()
        }
    }
}
