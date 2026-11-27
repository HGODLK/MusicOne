package com.musicone

import kotlinx.coroutines.CancellationException

/** 首选杜比时优先用杜比缓存或在线拉杜比，普通音质缓存只在在线拉取失败时才兜底。 */
internal suspend fun resolvePlaybackCacheFirst(
    preferExactOnline: Boolean,
    cached: suspend (allowOtherQuality: Boolean) -> ResolvedPlayback?,
    online: suspend () -> ResolvedPlayback,
): ResolvedPlayback {
    cached(!preferExactOnline)?.let { return it }
    return try {
        online()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        if (!preferExactOnline || error.stopsSourcePlaybackFallback()) throw error
        cached(true) ?: throw error
    }
}
