package com.musicone.demo

import kotlinx.coroutines.CancellationException

/** 在线首选杜比时先取同档缓存或在线音源，普通缓存只在在线解析失败后兜底。 */
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
        if (!preferExactOnline || error.stopsPlaybackFallback()) throw error
        cached(true) ?: throw error
    }
}
