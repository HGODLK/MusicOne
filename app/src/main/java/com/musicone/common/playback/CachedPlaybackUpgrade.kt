package com.musicone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 先用本地缓存起播，后台再尝试拉取更高音质；如果失败、取消或还是同一档位，就继续播当前的。 */
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
            // 稍微延后一点再请求，错开刚起播时加载封面和歌词的高峰。
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
