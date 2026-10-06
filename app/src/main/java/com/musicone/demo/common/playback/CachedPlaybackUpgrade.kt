package com.musicone.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 缓存先出声；后台准备可用档位，失败、取消或返回同档位都保留现有播放。 */
internal class CachedPlaybackUpgrade(
    private val scope: CoroutineScope,
    private val settleDelayMs: Long = PLAYBACK_CACHED_UPGRADE_DELAY_MS,
    private val resolve: suspend (MusicTrack, AudioQuality) -> ResolvedPlayback,
) {
    private var job: Job? = null

    fun start(initial: ResolvedPlayback, preferred: AudioQuality, isCurrent: () -> Boolean,
        onReady: (ResolvedPlayback) -> Unit) {
        cancel()
        if (offlinePlaybackKey(initial.source.url) == null || initial.source.actualQuality == preferred) return
        job = scope.launch {
            // 错开本地起播、封面与歌词交接，不在点击瞬间并发换票。
            delay(settleDelayMs)
            if (!isCurrent()) return@launch
            val next = try { withTimeoutOrNull(15_000) { resolve(initial.track, preferred) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (isCurrent() && next != null && usableCachedPlaybackUpgrade(initial, next)) onReady(next)
        }
    }

    fun cancel() { job?.cancel(); job = null }
}

internal fun usableCachedPlaybackUpgrade(initial: ResolvedPlayback, next: ResolvedPlayback): Boolean =
    next.track.id == initial.track.id && next.source.url.isNotBlank() &&
        !next.source.trial && !next.source.verificationPending &&
        next.source.actualQuality != initial.source.actualQuality
