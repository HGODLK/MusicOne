package com.musicone

import com.musicone.source.sdk.SourceCollectionCachePolicy
import com.musicone.sourceintegration.SourcePackageController
import com.musicone.sourceintegration.toSourceCollection

/** 已加载的音源包可以自定义哪些歌单进缓存目录；包没装或旧版包时保留全部已有快照。 */
internal fun isCacheLibraryPlaylist(playlist: MusicPlaylist,
    policy: SourceCollectionCachePolicy? = SourcePackageController.current()?.running(playlist.source.id)?.entry?.collections as? SourceCollectionCachePolicy,
): Boolean = !playlist.id.startsWith("local-cached-") && !playlist.id.startsWith("cache-track:") &&
    (policy?.let { runCatching { it.includesInCacheLibrary(playlist.toSourceCollection()) }.getOrDefault(true) } ?: true)

/** 统一按当前音源包规则过滤并去重缓存歌单，不可用音源包的旧目录照常保留。 */
internal fun canonicalCachePlaylists(playlists: List<MusicPlaylist>): List<MusicPlaylist> =
    playlists.filter { isCacheLibraryPlaylist(it) }.distinctBy(::cachePlaylistKey)

internal fun replaceCacheProviderPlaylists(previous: List<MusicPlaylist>, source: MusicSource,
    fresh: List<MusicPlaylist>): List<MusicPlaylist> = canonicalCachePlaylists(previous.filter { it.source != source } + fresh)

internal fun List<MusicPlaylist>.matchingCachePlaylistQuery(query: String): List<MusicPlaylist> {
    val keyword = query.trim()
    return if (keyword.isBlank()) this else filter { it.title.contains(keyword, ignoreCase = true) }
}
