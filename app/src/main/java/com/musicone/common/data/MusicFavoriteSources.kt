package com.musicone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** 失败或缺包时保留已有离线收藏，成功源只替换自己已知的索引。 */
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
