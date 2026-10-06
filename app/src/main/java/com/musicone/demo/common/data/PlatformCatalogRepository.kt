package com.musicone.demo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface PlatformCatalogAdapter {
    val source: MusicSource
    suspend fun search(query: String): List<MusicTrack>
    suspend fun searchCollections(query: String): List<MusicPlaylist> = emptyList()
    fun recommendedPlaylists(page: Int): List<MusicPlaylist>
    fun recommendedTracks(page: Int): List<MusicTrack>
    fun userPlaylists(): List<MusicPlaylist> = emptyList()
    suspend fun playlistDetail(playlist: MusicPlaylist): MusicPlaylist
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

    suspend fun recommendedTracks(source: MusicSource, page: Int): List<MusicTrack> =
        onAdapter(source) { it.recommendedTracks(page) }

    suspend fun userPlaylists(source: MusicSource): List<MusicPlaylist> =
        onAdapter(source) { it.userPlaylists() }

    suspend fun playlistDetail(playlist: MusicPlaylist): MusicPlaylist =
        onAdapter(playlist.source) { it.playlistDetail(playlist) }

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
    override fun recommendedTracks(page: Int): List<MusicTrack> = api.recommendedTracks(cookie(), page)
    override suspend fun playlistDetail(playlist: MusicPlaylist): MusicPlaylist = api.playlistDetail(playlist, cookie())
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
    override fun recommendedTracks(page: Int): List<MusicTrack> = session().let {
        val accountId = it.account?.userId.orEmpty().ifBlank { "guest" }
        val tracks = dailyMixStore.read(accountId) ?: personalized.dailyTracks(
            credential = it.credential,
            deviceId = it.deviceId,
            hasVipAccess = it.account?.hasVipAccess == true,
        ).also { tracks -> dailyMixStore.save(accountId, tracks) }
        artworkAliases.remember(QqTrackAccessResolver().resolve(tracks, it.credential, fresh = false))
    }
    override suspend fun playlistDetail(playlist: MusicPlaylist): MusicPlaylist = session().let {
        val detail = if (playlist.id == QQ_RECENT_SONGS_PLAYLIST_ID) {
            playlist
        } else if (playlist.isQqSearchAlbum) {
            QqAlbumRepository().load(playlist, it.credential, it.account?.hasVipAccess == true)
        } else if (playlist.id.startsWith(QQ_PROFILE_DIRECTORY_ID_PREFIX)) {
            library.profileDirectoryDetail(playlist, it.credential, it.account?.hasVipAccess == true)
        } else {
            api.playlistDetail(playlist, it.credential, it.account?.hasVipAccess == true)
        }
        playlistArtworkAliases.resolve(detail.copy(tracks = QqTrackAccessResolver().resolve(
            detail.tracks, it.credential, fresh = false)), it.credential)
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
    override fun recommendedTracks(page: Int): List<MusicTrack> = session().let {
        api.recommendedTracks(it.credential, page, hasVipAccess = it.account?.hasVipAccess == true)
    }
    override fun userPlaylists(): List<MusicPlaylist> = session().let {
        api.userPlaylists(it.credential, it.account?.userId.orEmpty())
    }
    override suspend fun playlistDetail(playlist: MusicPlaylist): MusicPlaylist = session().let {
        api.playlistDetail(playlist.remoteId(), it.credential, it.account?.hasVipAccess == true)
            .copy(isOwned = playlist.isOwned)
    }
}
