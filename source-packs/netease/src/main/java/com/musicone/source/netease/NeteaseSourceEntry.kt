package com.musicone.source.netease

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.musicone.source.sdk.*

internal val LocalNeteaseReduceMotion = staticCompositionLocalOf { false }
class NeteaseSourceEntry : SourceEntry, SourceMembershipProvider {
    override val descriptor = SourceDescriptor(NETEASE_SOURCE_ID, "网易云音乐", SourceKind.MAIN, 1, "0.1",
        setOf(SourceCapability.HOME, SourceCapability.SEARCH, SourceCapability.ACCOUNT, SourceCapability.PLAYLIST,
            SourceCapability.PLAYBACK, SourceCapability.LYRICS, SourceCapability.SETTINGS),
        useCustomPages = true, requiresLogin = true,
        permissions = SourcePermissions(adjustLineOffset = true),
        presentation = SourcePresentation(pageColorArgb = NETEASE_PAGE_COLOR, lineLeadMs = 565, wordLeadMs = 0))
    override val implementedCapabilities = descriptor.capabilities
    override val pageState: kotlinx.coroutines.flow.StateFlow<SourcePageState>? get() = if (::owner.isInitialized) owner.pageState else null
    private lateinit var owner: NeteasePackageState
    private val membershipService = NeteaseMembershipDetection { if (::owner.isInitialized) owner else null }
    override val membershipDetector: SourceMembershipDetector = membershipService
    private val authenticationService = NeteaseSourceAuthentication { owner }
    private val services = NeteaseSourceServices { owner }
    private val visualService = NeteaseSourceVisuals { owner }
    override val visuals: SourceVisuals = visualService
    private val loginPage = NeteaseAuthenticationPage({ owner }, authenticationService)
    private lateinit var trackActions: NeteaseTrackActions
    override val authentication: SourceAuthentication = authenticationService
    override val authenticationPage: SourceAuthenticationPage = loginPage
    override val discovery: SourceDiscovery = services
    override val search: SourceSearch = services
    override val collections: SourceCollections = services
    override val favorites: SourceFavorites = services
    override val collectionEditing: SourceCollectionEditing = services
    override val mediaPlayback: SourceMediaPlayback = services
    override val lyricProvider: SourceLyricProvider = services
    override val trackTheme: SourceTrackTheme = services
    override val settings: SourceSettings = object : SourceSettings {
        @Composable override fun Content() { NeteaseSettingsContent(owner, authenticationService) }
    }
    override fun initialize(environment: SourceEnvironment) {
        check(!::owner.isInitialized); owner = NeteasePackageState(environment); trackActions = NeteaseTrackActions(owner, services)
    }
    override fun onBack(): Boolean {
        if (trackActions.back()) return true
        if (owner.page == "authentication" && loginPage.back()) return true
        return owner.back()
    }
    override fun close() { if (::owner.isInitialized) { loginPage.cancel(); owner.close() } }
    @Composable override fun Content(route: SourceRoute) {
        val active = route.arguments["active"] != "false"
        SideEffect { owner.bottomInset = route.arguments["bottomInsetDp"]?.toFloatOrNull()?.coerceAtLeast(0f) ?: 80f }
        LaunchedEffect(route.page, route.arguments["id"], active) {
            if (!active) return@LaunchedEffect
            val page = route.page.lowercase()
            if (page in setOf("home", "search", "my", "authentication", "settings")) owner.primary(page)
            if (page == "playlist") route.arguments["id"]?.let { id -> owner.openPlaylist(NeteasePlaylist("netease-$id", NeteaseOrigin.NETEASE,
                route.arguments["title"].orEmpty(), "", "", 0, NETEASE_PAGE_COLOR, NETEASE_PAGE_COLOR, "", emptyList(), remoteKey = id)) }
        }
        val colors = MaterialTheme.colorScheme.copy(primary = NeteaseMusicThemeColor)
        CompositionLocalProvider(LocalNeteaseReduceMotion provides owner.environment.host.reduceMotion) {
            MaterialTheme(colorScheme = colors) {
                val background = rememberNeteaseBackgroundUiController(visualService::refresh)
                val visiblePage = owner.navigation.visible(route.page.lowercase(), active)
                Box(Modifier.fillMaxSize()) {
                    when (visiblePage) {
                        "authentication" -> loginPage.Content()
                        "my" -> {
                            key(owner.dataRevision) {
                                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (owner.account != null) owner.refreshLibrary() }
                            }
                            NeteaseProfileBackdrop(background.visual, Modifier.fillMaxSize())
                            NeteaseAdaptiveForeground(background.visual) { NeteaseMyScreen(owner.account, owner.catalog, background.visual, owner.bottomInset.dp,
                                owner::openPlaylist, { owner.refreshLibrary() }, background.hasCustomGlobal, background.chooseGlobal, background.restoreGlobal) }
                            if (owner.account == null) TextButton(onClick = { owner.open("authentication") }, modifier = Modifier.padding(top = 42.dp, start = 20.dp)) { Text("登录网易云音乐") }
                        }
                        "search" -> NeteaseSearchContent(owner, trackActions)
                        "playlist" -> NeteasePlaylistContent(owner, trackActions, background)
                        "settings" -> NeteaseSettingsContent(owner, authenticationService)
                        else -> NeteaseHomeContent(owner, trackActions)
                    }
                    if (active) { trackActions.Dialogs(); background.dialogContent() }
                }
            }
        }
    }
}
