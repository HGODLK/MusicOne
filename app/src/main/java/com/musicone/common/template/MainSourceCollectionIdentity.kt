package com.musicone

import com.musicone.source.sdk.SourceCollectionFavoritePolicy
import com.musicone.source.sdk.SourceFavorites
import com.musicone.sourceintegration.toSourceCollection

/** 收藏资格与等价键由音源提供，稳定 ID 继续用于原列表、缓存和导航。 */
internal fun MusicPlaylist.canCollectWith(service: SourceFavorites?, accountId: String?): Boolean =
    canCollectAsPlaylist() && (service as? SourceCollectionFavoritePolicy)?.canFavorite(toSourceCollection(accountId)) != false

internal fun sameSourceCollection(first: MusicPlaylist, second: MusicPlaylist, service: SourceFavorites?, accountId: String?): Boolean {
    if (first.source != second.source) return false
    if (first.id == second.id) return true
    val policy = service as? SourceCollectionFavoritePolicy ?: return false
    val keys = policy.favoriteIdentityKeys(first.toSourceCollection(accountId))
    return keys.isNotEmpty() && policy.favoriteIdentityKeys(second.toSourceCollection(accountId)).any(keys::contains)
}
