package com.musicone.demo

import android.content.Context

internal fun cachePlaylistKey(playlist: MusicPlaylist) = "${playlist.source.name}|${playlist.id}"

/** 目录按账号保存；容量全局共享，浏览不同音源不重建缓存任务。 */
internal class CachePlaylistDirectory(private val context: Context) {
    private val preferences = PlatformPreferences(context)
    private val snapshots = CachedPlaylistStore(context)
    private val catalog = PlatformCatalogRepository(context)
    private val library = MusicLibraryStore.get(context)
    private val bili = BiliCachePlaylistDirectory(context)

    fun signature(): List<Pair<String, String>> = MusicSource.entries.map {
        val session = preferences.readSession(it)
        session.cacheNamespace() to session.credential
    } + listOf("bilibili" to BiliSessionStore(context).read()?.mid.orEmpty())

    fun cached(): List<MusicPlaylist> {
        val saved = listOf(MusicSource.QQ, MusicSource.NETEASE, MusicSource.KUGOU).flatMap {
            snapshots.read(preferences.readSession(it).cacheNamespace())
        }
        return canonicalCachePlaylists(library.state.value.playlists + saved + bili.cached())
            .distinctBy(::cachePlaylistKey).map { playlist ->
                val stored = saved.firstOrNull { cachePlaylistKey(it) == cachePlaylistKey(playlist) }
                if (playlist.tracks.isEmpty() && stored != null) playlist.copy(tracks = stored.tracks) else playlist
            }
    }

    suspend fun remote(source: MusicSource): List<MusicPlaylist> {
        if (source == MusicSource.BILIBILI) return bili.remote()
        val session = preferences.readSession(source)
        val account = session.account ?: return emptyList()
        if (session.credential.isBlank()) return emptyList()
        val playlists = when (source) {
            MusicSource.QQ -> {
                val client = QqLibraryClient()
                listOf(client.favoritePlaylistPage(session.credential, account.userId, account.hasVipAccess).playlist) +
                    client.createdPlaylists(session.credential, account.userId) + client.collectedPlaylists(session.credential, account.userId)
            }
            MusicSource.KUGOU -> KugouSessionClient().library(session.credential, loadFavoriteTracks = false).let {
                listOfNotNull(it.favorite) + it.created + it.collected
            }
            MusicSource.NETEASE -> catalog.userPlaylists(source)
            else -> emptyList()
        }
        check(preferences.readSession(source) == session) { "账号已变化，请重新打开缓存管理" }
        return canonicalCachePlaylists(playlists)
    }

    fun signedIn(source: MusicSource): Boolean = if (source == MusicSource.BILIBILI) BiliSessionStore(context).read() != null
        else preferences.readSession(source).let { it.account != null && it.credential.isNotBlank() }

    suspend fun load(playlist: MusicPlaylist): MusicPlaylist {
        if (playlist.source == MusicSource.LOCAL) return library.state.value.playlists.firstOrNull { it.id == playlist.id } ?: playlist
        if (playlist.source == MusicSource.BILIBILI) return bili.load(playlist)
        val detail = catalog.playlistDetail(playlist).copy(isOwned = playlist.isOwned, isPlatformFavorites = playlist.isPlatformFavorites)
        if (detail.isPrimaryCachePlaylist && detail.tracks.size >= detail.count) {
            val session = preferences.readSession(detail.source)
            MusicDiskCache.get(context).setOrderedFavorites(session.cacheNamespace(), detail.tracks)
        }
        return detail
    }

    fun save(playlists: List<MusicPlaylist>) {
        listOf(MusicSource.QQ, MusicSource.NETEASE, MusicSource.KUGOU).forEach { source ->
            snapshots.save(preferences.readSession(source).cacheNamespace(), playlists.filter { it.source == source && isCacheLibraryPlaylist(it) })
        }
    }

    suspend fun cleanupPlaylists(): List<MusicPlaylist> {
        val initial = signature()
        val all = cached().associateBy(::cachePlaylistKey).toMutableMap()
        for (provider in listOf(MusicSource.QQ, MusicSource.NETEASE, MusicSource.KUGOU, MusicSource.BILIBILI)) {
            val fresh = remote(provider)
            if (signedIn(provider)) all.entries.removeAll { it.value.source == provider }
            fresh.forEach { all[cachePlaylistKey(it)] = it }
        }
        val complete = all.values.map { playlist ->
            val detail = if (playlist.tracks.size < playlist.count) load(playlist) else playlist
            check(detail.tracks.size >= detail.count) { "歌单歌曲尚未加载完整，请联网后重试" }
            detail
        }
        check(signature() == initial) { "账号已变化，请重新清理" }
        return complete
    }
}
