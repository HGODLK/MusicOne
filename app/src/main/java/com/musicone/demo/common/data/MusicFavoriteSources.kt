package com.musicone.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** 综合歌单按歌曲所属平台校验；单个平台暂时不可用时保留该平台已有状态。 */
internal suspend fun MusicFavoriteRepository.likedTrackIdsAcrossSources(previous: Set<String>): Set<String> = coroutineScope {
    listOf(MusicSource.QQ, MusicSource.NETEASE, MusicSource.KUGOU).map { source ->
        async {
            try {
                likedTrackIds(source)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val prefix = source.name.lowercase() + "-"
                previous.filterTo(linkedSetOf()) { it.startsWith(prefix) }
            }
        }
    }.flatMap { it.await() }.toSet()
}
