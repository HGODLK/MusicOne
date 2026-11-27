package com.musicone

import com.musicone.source.sdk.SourceCollectionFavoritePolicy
import com.musicone.source.sdk.SourceFavorites
import com.musicone.sourceintegration.toSourceCollection

/** 能不能收藏由音源自己的策略判断，列表、缓存和导航继续用原来的稳定歌单 ID。 */
internal fun MusicPlaylist.canCollectWith(service: SourceFavorites?, accountId: String?): Boolean =
    canCollectAsPlaylist() && (service as? SourceCollectionFavoritePolicy)?.canFavorite(toSourceCollection(accountId)) != false

internal fun sameSourceCollection(first: MusicPlaylist, second: MusicPlaylist, service: SourceFavorites?, accountId: String?): Boolean {
    if (first.source != second.source) return false
    if (first.id == second.id) return true
    val policy = service as? SourceCollectionFavoritePolicy ?: return false
    val keys = policy.favoriteIdentityKeys(first.toSourceCollection(accountId))
    return keys.isNotEmpty() && policy.favoriteIdentityKeys(second.toSourceCollection(accountId)).any(keys::contains)
}
