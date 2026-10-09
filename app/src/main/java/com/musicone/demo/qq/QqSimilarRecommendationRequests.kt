package com.musicone.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 独立种子最多同时请求两页，仍按原顺序消费，保留排重、首屏交付和刷新替换顺序。 */
internal suspend fun loadOrderedQqSimilarRecommendations(
    baseTracks: List<MusicTrack>,
    load: suspend (MusicTrack) -> QqSimilarRecommendation,
    consume: suspend (QqSimilarRecommendation) -> Boolean,
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
