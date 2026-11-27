package com.musicone.source.kugou

import com.musicone.source.sdk.*

/** 给旧专辑接口返回的曲目补齐封面和专辑名等展示字段，不改动歌曲 ID、播放参数和权限信息。 */
internal fun SourcePage<SourceTrackDetails>.withKugouAlbumCollectionMetadata(collection: SourceCollection): SourcePage<SourceTrackDetails> {
    val remote = collection.identity.remoteId
    val albumId = remote.takeIf { it.startsWith("album:") }?.removePrefix("album:")
        ?.takeIf { it.isNotBlank() && it.all(Char::isDigit) && it.any { digit -> digit != '0' } }
        ?: return this
    if (collection.identity.sourceId != KUGOU_SOURCE_ID) return this
    return copy(items = items.map { value ->
        if (value.track.identity.sourceId != KUGOU_SOURCE_ID) return@map value
        val artwork = if (value.artwork.url.isNullOrBlank()) {
            val existing = value.track.artworkUrl?.takeIf(String::isNotBlank)
            when {
                existing != null -> value.artwork.copy(url = existing)
                !collection.artwork.url.isNullOrBlank() -> collection.artwork
                else -> value.artwork
            }
        } else value.artwork
        value.copy(
            album = value.album.ifBlank { collection.title },
            albumRef = value.albumRef ?: SourceEntityRef(albumId, collection.title, "album"),
            artwork = artwork,
            track = if (value.track.artworkUrl.isNullOrBlank() && !artwork.url.isNullOrBlank())
                value.track.copy(artworkUrl = artwork.url) else value.track,
        )
    })
}
