package com.musicone.demo

/** “我喜欢”使用收藏顺序首曲的封面，QQ 返回的爱心图不作为歌曲封面的后备。 */
internal fun MusicPlaylist.withQqFavoriteArtwork(): MusicPlaylist {
    if (source != MusicSource.QQ || id != QQ_FAVORITES_PLAYLIST_ID) return this
    val latest = tracks.firstOrNull()
    return copy(artworkUrl = latest?.artworkUrl?.takeIf(String::isNotBlank),
        artworkStart = latest?.artworkStart ?: artworkStart,
        artworkEnd = latest?.artworkEnd ?: artworkEnd)
}
