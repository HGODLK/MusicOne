package com.musicone.source.runtime

import androidx.compose.runtime.Composable
import com.musicone.source.sdk.*

// 每次请求前后检查租约，移除时取消本实例子任务，禁止迟到结果提交到宿主。
internal class SourceGuardedEntry(private val original: SourceEntry, private val operations: SourceOperationLease) : SourceEntry by original, SourceSupplementalPageProviderAccess, SourcePlayerUiProviderAccess, SourceMembershipProvider {
    override val membershipDetector: SourceMembershipDetector?
        get() = operations.read { original.membershipDetectorOrNull()?.let { detector ->
            val checkOwner = operations.deferredCheck()
            object : SourceMembershipDetector {
                override val currentMembership get() = operations.read { checkOwner(); detector.currentMembership }
                override suspend fun detectMembership() = operations.call { checkOwner(); detector.detectMembership() }
            }
        } }
    override val playerUiProvider by lazy { original.playerUiProviderOrNull()?.let { guardSourcePlayerUi(it, original.descriptor.id, operations) } }
    override val supplementalPageProvider by lazy { original.supplementalPageProviderOrNull()?.let { guardSupplementalPages(it, original.descriptor.id, operations) } }
    override val visuals by lazy { original.visuals?.let { service -> object : SourceVisuals {
        override val supportsCustomBackground get() = operations.read { service.supportsCustomBackground }
        override fun observe(route: SourceRoute) = operations.read { service.observe(route) }
        override suspend fun setCustomBackground(route: SourceRoute, croppedImageUri: String?) = operations.call { service.setCustomBackground(route, croppedImageUri) }
    } } }
    override val playback by lazy { original.playback?.let { service -> object : SourcePlayback {
        override suspend fun resolve(track: SourceTrack, quality: String?) = operations.call { service.resolve(track, quality) }
    } } }
    override val catalog by lazy { original.catalog?.let { service -> object : SourceCatalog {
        override suspend fun tracks(page: SourceRoute, cursor: String?) = operations.call { service.tracks(page, cursor) }
    } } }
    override val authentication by lazy { original.authentication?.let { service -> object : SourceAuthentication {
        override val methods get() = operations.read { service.methods }
        override fun currentAccount() = operations.read { service.currentAccount() }
        override suspend fun begin(methodId: String, input: Map<String, String>) = operations.call(checkAccount = false) { service.begin(methodId, input) }
        override suspend fun continueAuthentication(challenge: SourceAuthChallenge, input: Map<String, String>) = operations.call(checkAccount = false) { service.continueAuthentication(challenge, input) }
        override suspend fun refreshAccount() = operations.call(checkAccount = false) { service.refreshAccount() }
        override suspend fun logout() = operations.call(checkAccount = false) { service.logout() }
        override fun exportSession() = operations.read { service.exportSession() }
        override fun importLegacySession(session: SourceLegacySession) = operations.read { service.importLegacySession(session) }
    } } }
    override val discovery by lazy { original.discovery?.let { guardSourceDiscovery(it, operations) } }
    override val search by lazy { original.search?.let { guardSourceSearch(it, operations) } }
    override val entities by lazy { original.entities?.let { guardSourceEntities(it, operations) } }
    override val collections by lazy { original.collections?.let { guardSourceCollections(it, operations) } }
    override val favorites by lazy { original.favorites?.let { guardSourceFavorites(it, operations) } }
    override val collectionEditing by lazy { original.collectionEditing?.let { guardSourceCollectionEditing(it, operations) } }
    override val radio by lazy { original.radio?.let { service -> object : SourceRadio {
        override suspend fun next(previous: SourcePayload?) = operations.call { service.next(previous) }
        override suspend fun feedback(track: SourceTrackDetails, action: String) = operations.call { service.feedback(track, action) }
    } } }
    override val contents by lazy { original.contents?.let { service -> object : SourceContents {
        override suspend fun resolve(input: String) = operations.call { service.resolve(input) }
        override suspend fun detail(content: SourceContent) = operations.call { service.detail(content) }
    } } }
    override val childItems by lazy { original.childItems?.let { service -> object : SourceChildItems {
        override suspend fun children(content: SourceContent, request: SourceCursorRequest) = operations.call { service.children(content, request) }
    } } }
    override val remoteCollections by lazy { original.remoteCollections?.let { service ->
        val guarded = object : SourceRemoteCollections {
            override suspend fun collections(request: SourceCursorRequest) = operations.call { service.collections(request) }
            override suspend fun contents(collection: SourceRemoteCollection, query: String?, request: SourceCursorRequest) = operations.call { service.contents(collection, query, request) }
        }
        if (service is SourceRemoteCollectionArtwork) object : SourceRemoteCollections by guarded, SourceRemoteCollectionArtwork {
            override suspend fun artwork(collection: SourceRemoteCollection, policy: SourceFetchPolicy) = operations.call {
                service.artwork(collection, policy)
            }
        } else guarded
    } }
    override val importing by lazy { original.importing?.let { service -> object : SourceImporting {
        override suspend fun tracks(content: SourceContent, selectedChildren: List<SourceChildItem>) = operations.call { service.tracks(content, selectedChildren) }
    } } }
    override val subscriptions by lazy { original.subscriptions?.let { service -> object : SourceSubscriptions {
        override suspend fun page(collection: SourceRemoteCollection, request: SourceCursorRequest) = operations.call { service.page(collection, request) }
    } } }
    override val lyricProvider: SourceLyricProvider? by lazy { original.lyricProvider?.let { service -> object : SourceLyricProvider, SourceLyricBehavior {
        override val waitForSingleSwitchSettlement get() = operations.read { (service as? SourceLyricBehavior)?.waitForSingleSwitchSettlement ?: false }
        override val preserveVerbatimFallback get() = operations.read { (service as? SourceLyricBehavior)?.preserveVerbatimFallback ?: false }
        override val parserVersion get() = operations.read { service.parserVersion }
        override val refreshPlainLyricsForVerbatim get() = operations.read { service.refreshPlainLyricsForVerbatim }
        override suspend fun lyrics(track: SourceTrackDetails) = operations.call { service.lyrics(track) }
        override fun timing(track: SourceTrackDetails) = operations.read { service.timing(track) }
    } } }
    override val trackTheme by lazy { original.trackTheme?.let { service -> object : SourceTrackTheme {
        override suspend fun colorArgb(track: SourceTrackDetails) = operations.call { service.colorArgb(track) }
    } } }
    override val mediaPlayback: SourceMediaPlayback? by lazy {
        original.mediaPlayback?.let { guardSourceMediaPlayback(it, operations) }
    }
    override val settings by lazy { original.settings?.let { service -> object : SourceSettings {
        @Composable override fun Content() { operations.read { Unit }; service.Content() }
    } } }
    override val authenticationPage by lazy { original.authenticationPage?.let { service -> object : SourceAuthenticationPage {
        @Composable override fun Content() { operations.read { Unit }; service.Content() }
    } } }
    override fun onBack(): Boolean = operations.read { original.onBack() }
    @Composable override fun Content(route: SourceRoute) { operations.read { Unit }; original.Content(route) }
}

