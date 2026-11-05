package com.musicone.source.subsonic

import androidx.compose.runtime.Composable
import com.musicone.source.sdk.*
import com.musicone.source.subsonic.internal.*

/**
 * Subsonic／OpenSubsonic 主音源（Navidrome、Gonic、Airsonic、Ampache 等）。
 * 页面全部使用宿主公共主模板，包内只提供资料、播放和服务器管理页。
 */
class SubsonicSourceEntry : SourceEntry {
    override val descriptor = SourceDescriptor(SUBSONIC_SOURCE_ID, "Subsonic", SourceKind.MAIN, 1, "0.1",
        setOf(SourceCapability.HOME, SourceCapability.SEARCH, SourceCapability.ACCOUNT, SourceCapability.PLAYBACK,
            SourceCapability.PLAYLIST, SourceCapability.ARTIST, SourceCapability.ALBUM, SourceCapability.RADIO,
            SourceCapability.LYRICS), useCustomPages = false, requiresLogin = true,
        permissions = SourcePermissions(replaceManualLyrics = true, adjustLineOffset = true),
        // Subsonic 标志的橙黄色。
        presentation = SourcePresentation(0xFFE8920F, 0xFFB86A00, "随机漫游", SUBSONIC_LINE_LEAD_MS, 0,
            listOf(0xFFB86A00, 0xFFFDF5E8, 0xFFF5C77A, 0xFFFEF6EA, 0xFF7A4500),
            listOf(0xFFA35E00, 0xFFFCF2E3, 0xFFEDB862, 0xFFFAEEDA, 0xFF5E3500)))
    override val implementedCapabilities get() = descriptor.capabilities

    private lateinit var auth: SubsonicAuthentication
    private lateinit var page: SubsonicLoginPage
    private lateinit var catalogService: SubsonicCatalogService
    private lateinit var entityService: SubsonicEntityService
    private lateinit var library: SubsonicLibraryService
    private lateinit var playbackService: SubsonicPlaybackService
    private lateinit var lyricService: SubsonicLyricService
    private var registration: AutoCloseable? = null

    override val authentication: SourceAuthentication get() = auth
    override val authenticationPage: SourceAuthenticationPage get() = page
    override val discovery: SourceDiscovery get() = catalogService
    override val search: SourceSearch get() = catalogService
    override val entities: SourceEntities get() = entityService
    override val collections: SourceCollections get() = library
    override val favorites: SourceFavorites get() = library
    override val collectionEditing: SourceCollectionEditing get() = library
    override val mediaPlayback: SourceMediaPlayback get() = playbackService
    override val radio: SourceRadio get() = playbackService
    override val lyricProvider: SourceLyricProvider get() = lyricService

    override fun initialize(environment: SourceEnvironment) {
        val host = environment.host
        val client = SubsonicClient()
        val urls = SubsonicArtworkUrls(client)
        val store = SubsonicServerStore(environment.context)
        auth = SubsonicAuthentication(store, client, onLoginCompleted = host::exitSource, motionPreference = { host.reduceMotion })
        val requests = SubsonicRequests(auth)
        val home = SubsonicHomeFeed(client, urls)
        catalogService = SubsonicCatalogService(requests, client, urls, home, SubsonicDailyStore(environment.context))
        entityService = SubsonicEntityService(requests, client, urls)
        library = SubsonicLibraryService(requests, client, urls)
        playbackService = SubsonicPlaybackService(requests, client, urls, home)
        lyricService = SubsonicLyricService(requests, client)
        page = SubsonicLoginPage(auth) { host.contentUi?.components?.forSource(SUBSONIC_SOURCE_ID) }
        registration = host.registerTask { close() }
    }

    @Composable override fun Content(route: SourceRoute) { }

    override fun close() {
        val registered = registration; registration = null; registered?.close()
        if (::library.isInitialized) library.clear()
    }
}
