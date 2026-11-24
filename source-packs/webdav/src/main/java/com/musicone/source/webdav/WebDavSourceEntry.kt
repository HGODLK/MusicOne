package com.musicone.source.webdav

import androidx.compose.runtime.Composable
import com.musicone.source.sdk.*
import com.musicone.source.webdav.internal.*

/**
 * WebDAV 补充音源入口：
 * - 声明 kind=SUPPLEMENTAL、useCustomPages=false，并通过 SourceSupplementalPageProvider 逐页接管；
 * - HOME=CUSTOM 自绘文件浏览、书签与平板双栏，ACCOUNT/COLLECTIONS/COLLECTION/CONTENT 使用宿主公共页，LINK/SETTINGS 关闭。
 */
class WebDavSourceEntry : SourceEntry, SourceSupplementalPageProvider {

    override val descriptor = SourceDescriptor(
        id = WEBDAV_SOURCE_ID,
        name = WEBDAV_SOURCE_NAME,
        kind = SourceKind.SUPPLEMENTAL,
        versionCode = WEBDAV_VERSION_CODE,
        versionName = WEBDAV_VERSION_NAME,
        capabilities = setOf(
            SourceCapability.HOME,
            SourceCapability.ACCOUNT,
            SourceCapability.PLAYBACK,
            SourceCapability.REMOTE_COLLECTIONS,
            SourceCapability.IMPORT,
            SourceCapability.SUBSCRIPTION,
            SourceCapability.LYRICS,
        ),
        useCustomPages = false,
        requiresLogin = false,
        permissions = SourcePermissions(
            editTrackMetadata = true,
            replaceManualLyrics = true,
            adjustLineOffset = true,
        ),
        presentation = SourcePresentation(
            pageColorArgb = WEBDAV_PAGE_COLOR_ARGB,
            lineLeadMs = 0L,
            wordLeadMs = 0L,
        ),
    )

    override val implementedCapabilities: Set<SourceCapability> get() = descriptor.capabilities

    private lateinit var environment: SourceEnvironment
    private lateinit var store: WebDavStore
    private lateinit var client: WebDavClient
    private lateinit var metadataReader: WebDavMetadataReader
    private lateinit var auth: WebDavAuthentication
    private lateinit var loginPage: WebDavLoginPage
    private lateinit var playbackService: WebDavPlaybackService
    private lateinit var lyricService: WebDavLyricService
    private lateinit var collectionsService: WebDavRemoteCollections
    private var registration: AutoCloseable? = null

    // 由入口持有，离开书签、服务器等页面再回来时仍在原目录；只在主线程的页面组合中创建。
    private var browser: WebDavBrowserController? = null

    @Volatile
    private var backHandler: Pair<Any, () -> Boolean>? = null

    override val authentication: SourceAuthentication get() = auth
    override val authenticationPage: SourceAuthenticationPage get() = loginPage
    override val mediaPlayback: SourceMediaPlayback get() = playbackService
    override val lyricProvider: SourceLyricProvider get() = lyricService
    override val remoteCollections: SourceRemoteCollections get() = collectionsService
    override val importing: SourceImporting get() = collectionsService
    override val subscriptions: SourceSubscriptions get() = collectionsService

    override fun initialize(environment: SourceEnvironment) {
        this.environment = environment
        val host = environment.host
        store = WebDavStore(environment.dataDirectory)
        client = WebDavClient()
        metadataReader = WebDavMetadataReader(client, store)
        auth = WebDavAuthentication(
            store = store,
            client = client,
            onLoginCompleted = host::exitSource,
            motionPreference = { host.reduceMotion },
        )
        loginPage = WebDavLoginPage(auth) { host.contentUi?.components?.forSource(WEBDAV_SOURCE_ID) }
        playbackService = WebDavPlaybackService(auth, client, metadataReader)
        lyricService = WebDavLyricService(auth, client)
        collectionsService = WebDavRemoteCollections(auth, store, client, metadataReader)
        if (!environment.validationOnly) {
            registration = host.registerTask { close() }
        }
    }

    override fun supplementalPagePolicy(page: SourceSupplementalPage): SourceSupplementalPagePolicy = when (page) {
        SourceSupplementalPage.HOME -> SourceSupplementalPagePolicy(SourceSupplementalPageMode.CUSTOM)
        SourceSupplementalPage.ACCOUNT -> SourceSupplementalPagePolicy(SourceSupplementalPageMode.DEFAULT)
        SourceSupplementalPage.COLLECTIONS -> SourceSupplementalPagePolicy(SourceSupplementalPageMode.DEFAULT)
        SourceSupplementalPage.COLLECTION -> SourceSupplementalPagePolicy(SourceSupplementalPageMode.DEFAULT)
        SourceSupplementalPage.CONTENT -> SourceSupplementalPagePolicy(SourceSupplementalPageMode.DEFAULT)
        SourceSupplementalPage.LINK -> SourceSupplementalPagePolicy(SourceSupplementalPageMode.DISABLED)
        SourceSupplementalPage.SETTINGS -> SourceSupplementalPagePolicy(SourceSupplementalPageMode.DISABLED)
    }

    @Composable
    override fun SupplementalPage(context: SourceSupplementalPageContext) {
        when (context.page) {
            SourceSupplementalPage.HOME -> WebDavBrowserPage(
                context = context,
                host = environment.host,
                auth = auth,
                controller = browser(),
                bindBack = ::bindBack,
            )
            else -> context.PublicContent()
        }
    }

    @Composable
    override fun Content(route: SourceRoute) {
        // 逐页接管模式下业务页通过 SupplementalPage 与 authenticationPage 挂载。
    }

    override fun onBack(): Boolean = backHandler?.second?.invoke() == true

    private fun browser(): WebDavBrowserController = browser ?: WebDavBrowserController(
        environment.host, auth, client, metadataReader, collectionsService,
    ).also { browser = it }

    private fun bindBack(owner: Any, handler: (() -> Boolean)?) {
        if (handler != null) backHandler = owner to handler
        else if (backHandler?.first === owner) backHandler = null
    }

    override fun close() {
        val registered = registration
        registration = null
        registered?.close()
        backHandler = null
        browser?.close()
        browser = null
        if (::store.isInitialized) store.flushMetadata()
    }
}
