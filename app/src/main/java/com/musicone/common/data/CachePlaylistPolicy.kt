package com.musicone

import com.musicone.source.sdk.SourceCollectionCachePolicy
import com.musicone.sourceintegration.SourcePackageController
import com.musicone.sourceintegration.toSourceCollection

/** 活跃包可筛选账号目录；缺包或旧包不丢弃已有快照。 */
internal fun isCacheLibraryPlaylist(playlist: MusicPlaylist,
    policy: SourceCollectionCachePolicy? = SourcePackageController.current()?.running(playlist.source.id)?.entry?.collections as? SourceCollectionCachePolicy,
): Boolean = !playlist.id.startsWith("local-cached-") && !playlist.id.startsWith("cache-track:") &&
    (policy?.let { runCatching { it.includesInCacheLibrary(playlist.toSourceCollection()) }.getOrDefault(true) } ?: true)

/** 每个状态入口统一应用当前包规则；不可用包的旧目录继续保留。 */
internal fun canonicalCachePlaylists(playlists: List<MusicPlaylist>): List<MusicPlaylist> =
    playlists.filter { isCacheLibraryPlaylist(it) }.distinctBy(::cachePlaylistKey)

internal fun replaceCacheProviderPlaylists(previous: List<MusicPlaylist>, source: MusicSource,
    fresh: List<MusicPlaylist>): List<MusicPlaylist> = canonicalCachePlaylists(previous.filter { it.source != source } + fresh)

internal fun List<MusicPlaylist>.matchingCachePlaylistQuery(query: String): List<MusicPlaylist> {
    val keyword = query.trim()
    return if (keyword.isBlank()) this else filter { it.title.contains(keyword, ignoreCase = true) }
}
