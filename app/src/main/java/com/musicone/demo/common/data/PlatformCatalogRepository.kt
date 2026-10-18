package com.musicone.demo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class CatalogFetchPolicy {
    CACHE_FIRST,
    SERVER_VERIFY,
}

internal interface PlatformCatalogAdapter {
    val source: MusicSource
    suspend fun search(query: String): List<MusicTrack>
    suspend fun searchCollections(query: String): List<MusicPlaylist> = emptyList()
    fun recommendedPlaylists(page: Int): List<MusicPlaylist>
    fun recommendedTracks(page: Int, policy: CatalogFetchPolicy = CatalogFetchPolicy.CACHE_FIRST): List<MusicTrack>
    fun userPlaylists(): List<MusicPlaylist> = emptyList()
    suspend fun playlistDetail(playlist: MusicPlaylist): MusicPlaylist
    suspend fun playlistPage(playlist: MusicPlaylist, cursor: PlaylistTrackCursor?): PlaylistTrackPage =
        PlaylistTrackPage(playlistDetail(playlist))
}

internal class PlatformCatalogRepository(context: Context) {
    private val preferences = PlatformPreferences(context)
    private val qqArtworkAliases = QqArtworkAliasStore(context)
    private val adapters: Map<MusicSource, PlatformCatalogAdapter> = listOf(
        NeteaseCatalogAdapter(NeteaseApiClient()) { preferences.readSession(MusicSource.NETEASE) },
        QqCatalogAdapter(QqApiClient(), QqPersonalizedClient(), QqDailyMixStore(context), qqArtworkAliases) {
            preferences.readSession(MusicSource.QQ)
        },
        KugouCatalogAdapter(KugouApiClient()) { preferences.credential(MusicSource.KUGOU) },
    ).associateBy(PlatformCatalogAdapter::source)

    suspend fun search(source: MusicSource, query: String): List<MusicTrack> = onAdapter(source) { it.search(query) }
    suspend fun searchCollections(source: MusicSource, query: String): List<MusicPlaylist> =
        onAdapter(source) { it.searchCollections(query) }

    suspend fun recommendedPlaylists(source: MusicSource, page: Int): List<MusicPlaylist> =
        onAdapter(source) { it.recommendedPlaylists(page) }

    suspend fun recommendedTracks(
        source: MusicSource,
        page: Int,
        policy: CatalogFetchPolicy = CatalogFetchPolicy.CACHE_FIRST,
    ): List<MusicTrack> = onAdapter(source) { it.recommendedTracks(page, policy) }

    suspend fun userPlaylists(source: MusicSource): List<MusicPlaylist> =
        onAdapter(source) { it.userPlaylists() }

    suspend fun playlistDetail(playlist: MusicPlaylist): MusicPlaylist =
        onAdapter(playlist.source) { it.playlistDetail(playlist) }

    suspend fun playlistPage(playlist: MusicPlaylist, cursor: PlaylistTrackCursor?): PlaylistTrackPage =
        onAdapter(playlist.source) { it.playlistPage(playlist, cursor) }

    private suspend fun <T> onAdapter(source: MusicSource, block: suspend (PlatformCatalogAdapter) -> T): T =
        withContext(Dispatchers.IO) {
            val adapter = adapters[source] ?: throw IllegalStateException("${source.label}尚未接入内容服务")
            block(adapter)
        }
}

private class KugouCatalogAdapter(
    private val api: KugouApiClient,
    private val cookie: () -> String,
) : PlatformCatalogAdapter {
    override val source = MusicSource.KUGOU
    override suspend fun search(query: String): List<MusicTrack> = api.search(query, cookie())
    override suspend fun searchCollections(query: String): List<MusicPlaylist> = api.searchCollections(query, cookie())
    override fun recommendedPlaylists(page: Int): List<MusicPlaylist> = api.recommendedPlaylists(cookie(), page)
    override fun recommendedTracks(page: Int, policy: CatalogFetchPolicy): List<MusicTrack> =
        api.recommendedTracks(cookie(), page)
    override suspend fun playlistDetail(playlist: MusicPlaylist): MusicPlaylist = api.playlistDetail(playlist, cookie())
    override suspend fun playlistPage(playlist: MusicPlaylist, cursor: PlaylistTrackCursor?): PlaylistTrackPage =
        api.playlistPage(playlist, cookie(), cursor)
}

private class QqCatalogAdapter(
    private val api: QqApiClient,
    private val personalized: QqPersonalizedClient,
    private val dailyMixStore: QqDailyMixStore,
    private val artworkAliases: QqArtworkAliasStore,
    private val session: () -> PlatformSession,
) : PlatformCatalogAdapter {
    private val library = QqLibraryClient()
    private val playlistArtworkAliases = QqPlaylistArtworkAliasResolver(api, artworkAliases)
    override val source = MusicSource.QQ
    override suspend fun search(query: String): List<MusicTrack> = session().let {
        artworkAliases.remember(api.search(query, it.credential, it.account?.hasVipAccess == true))
    }
    override fun recommendedPlaylists(page: Int): List<MusicPlaylist> = session().let {
        personalized.recommendedPlaylists(it.credential, page)
    }
    override fun recommendedTracks(page: Int, policy: CatalogFetchPolicy): List<MusicTrack> = session().let {
        val accountId = it.account?.userId.orEmpty().ifBlank { "guest" }
        val tracks = when (policy) {
            CatalogFetchPolicy.CACHE_FIRST -> {
                dailyMixStore.read(accountId) ?: personalized.dailyTracks(
                    credential = it.credential,
                    deviceId = it.deviceId,
                    hasVipAccess = it.account?.hasVipAccess == true,
                ).also { fresh -> dailyMixStore.save(accountId, fresh) }
            }
            CatalogFetchPolicy.SERVER_VERIFY -> {
                personalized.dailyTracks(
                    credential = it.credential,
                    deviceId = it.deviceId,
                    hasVipAccess = it.account?.hasVipAccess == true,
                ).also { fresh -> dailyMixStore.save(accountId, fresh) }
            }
        }
        artworkAliases.remember(QqTrackAccessResolver().resolve(tracks, it.credential, fresh = false))
    }
    override suspend fun playlistDetail(playlist: MusicPlaylist): MusicPlaylist =
        collectPlaylistTrackPages { playlistPage(playlist, it) }

    override suspend fun playlistPage(playlist: MusicPlaylist, cursor: PlaylistTrackCursor?): PlaylistTrackPage = session().let {
        val page = if (playlist.id == QQ_RECENT_SONGS_PLAYLIST_ID) {
            PlaylistTrackPage(playlist)
        } else if (playlist.id == QQ_FAVORITES_PLAYLIST_ID) {
            library.favoritePlaylistPage(it.credential, it.account?.userId.orEmpty(),
                it.account?.hasVipAccess == true, cursor?.offset ?: 0)
        } else if (playlist.isQqSearchAlbum) {
            PlaylistTrackPage(QqAlbumRepository().load(playlist, it.credential, it.account?.hasVipAccess == true))
        } else if (playlist.id.startsWith(QQ_PROFILE_DIRECTORY_ID_PREFIX)) {
            library.profileDirectoryPage(playlist, it.credential, it.account?.hasVipAccess == true, cursor?.offset ?: 0)
        } else {
            QqPlaylistPaging().page(playlist, it.credential, it.account?.hasVipAccess == true, cursor?.offset ?: 0)
        }
        page.copy(playlist = playlistArtworkAliases.resolve(page.playlist.copy(tracks = QqTrackAccessResolver().resolve(
            page.playlist.tracks, it.credential, fresh = false)), it.credential))
    }
}

private class NeteaseCatalogAdapter(
    private val api: NeteaseApiClient,
    private val session: () -> PlatformSession,
) : PlatformCatalogAdapter {
    override val source = MusicSource.NETEASE
    override suspend fun search(query: String): List<MusicTrack> = session().let {
        api.search(query, it.credential, it.account?.hasVipAccess == true)
    }
    override fun recommendedPlaylists(page: Int): List<MusicPlaylist> = api.recommendedPlaylists(session().credential, page = page)
    override fun recommendedTracks(page: Int, policy: CatalogFetchPolicy): List<MusicTrack> = session().let {
        api.recommendedTracks(it.credential, page, hasVipAccess = it.account?.hasVipAccess == true)
    }
    override fun userPlaylists(): List<MusicPlaylist> = session().let {
        api.userPlaylists(it.credential, it.account?.userId.orEmpty())
    }
    override suspend fun playlistDetail(playlist: MusicPlaylist): MusicPlaylist = session().let {
        api.playlistDetail(playlist.remoteId(), it.credential, it.account?.hasVipAccess == true)
            .copy(isOwned = playlist.isOwned)
    }
    override suspend fun playlistPage(playlist: MusicPlaylist, cursor: PlaylistTrackCursor?): PlaylistTrackPage = session().let {
        api.playlistPage(playlist, it.credential, it.account?.hasVipAccess == true, cursor)
    }
}
