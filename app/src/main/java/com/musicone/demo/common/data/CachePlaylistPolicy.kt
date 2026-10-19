package com.musicone.demo

/** 酷狗账号歌单统一使用云歌单编号，推荐歌单不能混入目录或旧快照。 */
internal fun isCacheLibraryPlaylist(playlist: MusicPlaylist): Boolean =
    !playlist.id.startsWith("local-cached-") &&
        !playlist.id.startsWith("cache-track:") &&
        (playlist.source != MusicSource.KUGOU || playlist.id.startsWith("kugou-cloudlist:"))

/** 每个状态入口都执行同一目录约束，不能重新合并旧状态中的首页推荐。 */
internal fun canonicalCachePlaylists(playlists: List<MusicPlaylist>): List<MusicPlaylist> =
    playlists.filter(::isCacheLibraryPlaylist).distinctBy(::cachePlaylistKey)

internal fun replaceCacheProviderPlaylists(previous: List<MusicPlaylist>, source: MusicSource,
    fresh: List<MusicPlaylist>): List<MusicPlaylist> = canonicalCachePlaylists(previous.filter { it.source != source } + fresh)

internal fun List<MusicPlaylist>.matchingCachePlaylistQuery(query: String): List<MusicPlaylist> {
    val keyword = query.trim()
    return if (keyword.isBlank()) this else filter { it.title.contains(keyword, ignoreCase = true) }
}
