package com.musicone.source.bilibili

import androidx.compose.runtime.Composable
import com.musicone.source.sdk.*

class BilibiliSourceEntry : SourceEntry {
    override val descriptor = SourceDescriptor(BILI_SOURCE_ID, "哔哩哔哩", SourceKind.SUPPLEMENTAL, 1, "1.0",
        setOf(SourceCapability.HOME, SourceCapability.ACCOUNT, SourceCapability.PLAYBACK, SourceCapability.CONTENT,
            SourceCapability.CHILD_ITEMS, SourceCapability.REMOTE_COLLECTIONS, SourceCapability.IMPORT, SourceCapability.SUBSCRIPTION),
        useCustomPages = false, requiresLogin = false,
        permissions = SourcePermissions(editTrackMetadata = true, replaceManualLyrics = true, adjustLineOffset = true),
        presentation = SourcePresentation(pageColorArgb = BILI_PAGE_COLOR))
    override val implementedCapabilities = descriptor.capabilities
    private lateinit var state: BiliPackageState
    private val contentServices = BiliSourceContents { state }
    private val accountServices = BiliSourceAuthentication { state }
    private val loginPage = BiliSourceAuthenticationPage({ state }, accountServices)
    override val authentication: SourceAuthentication = accountServices
    override val authenticationPage: SourceAuthenticationPage = loginPage
    override val contents: SourceContents = contentServices
    override val childItems: SourceChildItems = contentServices
    override val remoteCollections: SourceRemoteCollections = contentServices
    override val importing: SourceImporting = contentServices
    override val subscriptions: SourceSubscriptions = contentServices
    override val mediaPlayback: SourceMediaPlayback = BiliSourcePlayback { state }
    override val trackTheme: SourceTrackTheme = object : SourceTrackTheme {
        override suspend fun colorArgb(track: SourceTrackDetails): Long? = BILI_PAGE_COLOR
    }
    override fun initialize(environment: SourceEnvironment) { check(!::state.isInitialized); state = BiliPackageState(environment) }
    override fun onBack(): Boolean = loginPage.onBack()
    override fun close() { if (::state.isInitialized) { accountServices.cancel(); state.close() } }
    @Composable override fun Content(route: SourceRoute) {
        check(route.page == "authentication") { "此音源内容页使用宿主公共补充模板，请通过公开数据能力访问" }
        authenticationPage.Content()
    }
}
