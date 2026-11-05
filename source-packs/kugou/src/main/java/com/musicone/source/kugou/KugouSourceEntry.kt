package com.musicone.source.kugou

import androidx.compose.runtime.Composable
import com.musicone.source.sdk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class KugouSourceEntry : SourceEntry, SourceMembershipProvider {
    override val descriptor = SourceDescriptor(KUGOU_SOURCE_ID, "酷狗音乐", SourceKind.MAIN, 2, "1.1",
        setOf(SourceCapability.PLAYBACK, SourceCapability.HOME, SourceCapability.SEARCH, SourceCapability.ACCOUNT,
            SourceCapability.PLAYLIST, SourceCapability.ARTIST, SourceCapability.ALBUM, SourceCapability.RADIO,
            SourceCapability.LYRICS),
        useCustomPages = true, requiresLogin = true,
        permissions = SourcePermissions(editTrackMetadata = false, replaceManualLyrics = false, adjustLineOffset = true),
        presentation = SourcePresentation(pageColorArgb = KUGOU_PAGE_COLOR, radioColorArgb = KUGOU_PAGE_COLOR,
            lineLeadMs = 185, wordLeadMs = 115))
    override val implementedCapabilities = descriptor.capabilities
    private lateinit var state: KugouPackageState
    override val membershipDetector: SourceMembershipDetector = KugouMembershipDeclaration {
        if (::state.isInitialized) state else null
    }
    private val catalogService = KugouSourceCatalog { state }
    private val discoveryService: KugouSourceDiscovery = KugouSourceDiscovery({ state }, catalogService::remember, recentChanged = { homeController.invalidateRecent(it) })
    private val searchService = KugouSourceSearch(state = { state })
    private val entityService = KugouSourceEntities(state = { state })
    private val collectionService = KugouCollectionService({ state }, catalogService)
    private val homeCollections = KugouHomeCollections({ state.account()?.userId }, collectionService)
    private val radioService = KugouSourceRadio(state = { state })
    private val authenticationService = KugouSourceAuthentication { state }
    private val playbackService = KugouSourcePlayback { state }
    private val homeControllerCache = lazy { KugouHomeController(state.scope,
        { KugouHomeOwner(state.account()?.userId, state.revision.get()) }, discoveryService, discoveryService,
        KugouHomeSnapshotStore(java.io.File(state.environment.dataDirectory, "home-snapshot-v1")),
        prepareArtwork = { snapshot -> prepareKugouHomeCachedArtwork(state.environment, snapshot) }) }
    private val homeController by homeControllerCache
    private val rootState = MutableStateFlow(SourcePageState())
    private val root by lazy { KugouPublicRoot(state, homeController, homeCollections,
        KugouHomeActions(state, homeCollections), rootState) }
    override val pageState = rootState.asStateFlow()
    private val loginPage = KugouSourceAuthenticationPage({ state }, authenticationService) {}
    override val authentication: SourceAuthentication = authenticationService
    override val authenticationPage: SourceAuthenticationPage = loginPage
    override val discovery: SourceDiscovery = discoveryService
    override val search: SourceSearch = searchService
    override val entities: SourceEntities = entityService
    override val collections: SourceCollections = homeCollections
    override val favorites: SourceFavorites = collectionService
    override val collectionEditing: SourceCollectionEditing = collectionService
    override val radio: SourceRadio = radioService
    override val mediaPlayback: SourceMediaPlayback = playbackService
    override val lyricProvider: SourceLyricProvider = playbackService
    override val trackTheme: SourceTrackTheme = object : SourceTrackTheme { override suspend fun colorArgb(track: SourceTrackDetails): Long? = KUGOU_PAGE_COLOR }
    override fun initialize(environment: SourceEnvironment) {
        check(!::state.isInitialized)
        state = KugouPackageState(environment) {
            if (homeControllerCache.isInitialized()) homeController.clearMemory()
            collectionService.clearMemory()
            catalogService.clearMemory()
            homeCollections.clearMemory()
        }
        if (!environment.validationOnly) homeController.restoreSnapshot()
    }
    override fun onBack(): Boolean = loginPage.onBack() || root.onBack()
    override fun close() { if (::state.isInitialized) { authenticationService.cancel(); state.close() } }
    // 首页只在包内组合公开组件，其余页面使用新公开整页入口。
    @Composable override fun Content(route: SourceRoute) {
        KugouRequiredLogin(state.preferences, authenticationService, loginPage, route.arguments["active"] != "false") {
            root.Content(route)
        }
    }
}
