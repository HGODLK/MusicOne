package com.musicone.source.qq.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 相似推荐并发拉取（最多同时请求 2 页），但结果仍按原顺序合并和去重。 */
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
