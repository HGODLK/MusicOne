package com.musicone.source.qq.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 高档位经常不存在且校验较慢，点播时并行准备的下一档。 */
internal fun qqSpeculativeFallback(quality: AudioQuality): AudioQuality? = when (quality) {
    AudioQuality.HI_RES, AudioQuality.DOLBY -> AudioQuality.LOSSLESS
    else -> null
}

/**
 * 点播高档位时并行换取下一档，宿主降档请求直接接上同一个任务。
 * 同一时间只保留当前歌曲的预取；高档位成功或切到别的歌时取消。
 */
internal class QqPlaybackFallbackPrefetch(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private data class Key(val cookie: String, val trackId: String, val quality: AudioQuality)
    private val pending = LinkedHashMap<Key, Deferred<PlaybackSource>>()

    fun start(track: MusicTrack, cookie: String, quality: AudioQuality,
              resolve: suspend () -> PlaybackSource): Deferred<PlaybackSource> {
        val key = Key(cookie, track.id, quality)
        val stale = mutableListOf<Deferred<PlaybackSource>>()
        val task = synchronized(pending) {
            pending[key]?.let { return it }
            pending.entries.removeAll { (other, task) -> (other.trackId != track.id).also { if (it) stale += task } }
            scope.async(start = CoroutineStart.LAZY) { resolve() }.also { pending[key] = it }
        }
        stale.forEach { it.cancel() }
        task.invokeOnCompletion { cause ->
            // 成功结果已进入短期音源缓存；失败保留给宿主降档时直接取走同一结果。
            if (cause == null || cause is CancellationException) synchronized(pending) { pending.remove(key, task) }
        }
        task.start()
        return task
    }

    fun cancel(task: Deferred<PlaybackSource>) {
        synchronized(pending) { pending.values.remove(task) }
        task.cancel()
    }

    /** 取走进行中或已失败的预取；预取被取消时返回 null，由调用方重新请求。 */
    suspend fun take(track: MusicTrack, cookie: String, quality: AudioQuality): PlaybackSource? {
        val task = synchronized(pending) { pending.remove(Key(cookie, track.id, quality)) } ?: return null
        return try {
            task.await()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        }
    }
}
