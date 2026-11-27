package com.musicone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** 汇总各音源的红心歌曲：拉取失败或没装包时保留旧数据，只覆盖拉取成功的音源。 */
internal suspend fun MusicFavoriteRepository.likedTrackIdsAcrossSources(previous: Set<String>): Set<String> = coroutineScope {
    val results = favoriteSources().map { source ->
        val known = knownFavoriteIds(source)
        async {
            try { known to likedTrackIds(source) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }.mapNotNull { it.await() }
    previous.toMutableSet().apply { results.forEach { (known, current) -> removeAll(known); addAll(current) } }
}
