package com.musicone.source.qq.internal

/** “我喜欢”歌单用最新收藏那首歌的封面，不用 QQ 默认的红心图。 */
internal fun MusicPlaylist.withQqFavoriteArtwork(): MusicPlaylist {
    if (source != MusicSource.QQ || id != QQ_FAVORITES_PLAYLIST_ID) return this
    val latest = tracks.firstOrNull()
    return copy(artworkUrl = latest?.artworkUrl?.takeIf(String::isNotBlank),
        artworkStart = latest?.artworkStart ?: artworkStart,
        artworkEnd = latest?.artworkEnd ?: artworkEnd)
}
