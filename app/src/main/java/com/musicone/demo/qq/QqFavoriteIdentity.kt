package com.musicone.demo

/** QQ 歌曲在不同接口中可能同时使用 songid、songmid 和本地 id。 */
internal fun MusicTrack.qqFavoriteIdentityIds(): Set<String> = buildSet {
    add(id)
    if (source != MusicSource.QQ) return@buildSet
    catalogId.toLongOrNull()?.takeIf { it > 0L }?.let { add("qq-$it") }
    qqPlaybackMid().takeIf(::isValidQqTrackMid)?.let { add("qq-$it") }
}

internal fun MusicTrack.isQqFavorite(favoriteIds: Set<String>): Boolean =
    qqFavoriteIdentityIds().any(favoriteIds::contains)
