package com.musicone.source.qq

import androidx.compose.runtime.Composable
import com.musicone.source.sdk.*
import com.musicone.source.qq.internal.QqSessionRequestCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull

/** QQ 协议与会话只在独立包内初始化，宿主使用公共模板展示资料。 */
class QqSourceEntry : SourceEntry, SourceMembershipProvider {
    override val descriptor = SourceDescriptor(QQ_SOURCE_ID, "QQ音乐", SourceKind.MAIN, 1, "1.0",
        setOf(SourceCapability.HOME, SourceCapability.SEARCH, SourceCapability.ACCOUNT, SourceCapability.PLAYBACK,
            SourceCapability.PLAYLIST, SourceCapability.ARTIST, SourceCapability.ALBUM, SourceCapability.RADIO,
            SourceCapability.LYRICS, SourceCapability.SETTINGS), false, true,
        SourcePermissions(adjustLineOffset = true), SourcePresentation(0xFF31C27C, 0xFF09664C, "猜你喜欢", 275, 105,
            listOf(0xFF09664C, 0xFFF4FAED, 0xFF9EE7B9, 0xFFF3FAED, 0xFF073D2F),
            listOf(0xFF0D624B, 0xFFF2F8E9, 0xFF8ED5B0, 0xFFEBF5DF, 0xFF133B2B)))
    override val implementedCapabilities get() = descriptor.capabilities
    private lateinit var auth: QqAuthentication
    private lateinit var services: QqServices
    private lateinit var pages: QqPackagePages
    private val membershipService = QqMembershipDetection(session = { if (::auth.isInitialized) auth.membershipSession() else null })
    override val membershipDetector: SourceMembershipDetector = membershipService
    private var scope: CoroutineScope? = null
    private var registration: AutoCloseable? = null
    override val authentication: SourceAuthentication get() = auth
    override val authenticationPage: SourceAuthenticationPage get() = pages
    override val settings: SourceSettings = object : SourceSettings {
        @Composable override fun Content() { QqQualitySettings(services) }
    }
    override val playback: SourcePlayback get() = services
    override val mediaPlayback: SourceMediaPlayback get() = services
    override val catalog: SourceCatalog get() = services
    override val discovery: SourceDiscovery get() = services
    override val search: SourceSearch get() = services
    override val entities: SourceEntities get() = services.entityService
    override val collections: SourceCollections get() = services
    override val favorites: SourceFavorites get() = services
    override val collectionEditing: SourceCollectionEditing get() = services
    override val radio: SourceRadio get() = services
    override val lyricProvider: SourceLyricProvider get() = services
    override val trackTheme: SourceTrackTheme get() = services
    override fun initialize(environment: SourceEnvironment) {
        auth = QqAuthentication(environment.context, environment.host::exitSource) { environment.host.reduceMotion }
        services = QqServices(environment.context, auth)
        pages = QqPackagePages(auth)
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = jobs
        registration = environment.host.registerTask { close() }
        if (!environment.validationOnly) jobs.launch {
            // 长时间没有请求时也提前续票；未到期只读本地时间，不联网。
            while (true) {
                delay(QQ_TICKET_CHECK_INTERVAL_MS)
                try { auth.renewIfDue() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
            }
        }
        jobs.launch {
            QqSessionRequestCoordinator.verification.filterNotNull().collect {
                val ui = environment.host.ui
                if (ui == null || environment.validationOnly) {
                    QqSessionRequestCoordinator.cancel()
                    environment.host.reportError("QQ播放需要完成安全验证")
                } else ui.openDestination("source-authentication", mapOf("sourceId" to QQ_SOURCE_ID))
            }
        }
    }
    @Composable override fun Content(route: SourceRoute) { }
    override fun close() {
        scope?.cancel(); scope = null
        val registered = registration; registration = null; registered?.close()
        if (::auth.isInitialized) auth.close()
        if (::services.isInitialized) services.close()
    }
}

private const val QQ_TICKET_CHECK_INTERVAL_MS = 5 * 60 * 1000L
