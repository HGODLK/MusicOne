package com.musicone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 多个种子最多 2 路并发请求，但按原顺序依次产出，保证去重和首屏展示顺序稳定。 */
internal suspend fun loadOrderedMainSourceSimilarRecommendations(
    baseTracks: List<MusicTrack>,
    load: suspend (MusicTrack) -> MainSourceSimilarRecommendation,
    consume: suspend (MainSourceSimilarRecommendation) -> Boolean,
) = coroutineScope {
    val limiter = Semaphore(2)
    val requests = baseTracks.map { base ->
        async {
            try {
                Result.success(limiter.withPermit { load(base) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
    }
    try {
        for (request in requests) {
            if (!consume(request.await().getOrThrow())) break
        }
    } finally {
        requests.forEach { it.cancel() }
    }
}
