package com.musicone.source.sdk

import androidx.compose.runtime.Composable

/** 简易入口的静态信息；额外能力仅在确实提供服务时声明。 */
data class SimpleSupplementalSourceInfo(
    val id: String,
    val name: String,
    val versionCode: Long,
    val versionName: String,
    val extraCapabilities: Set<SourceCapability> = emptySet(),
    val requiresLogin: Boolean = false,
    val permissions: SourcePermissions = SourcePermissions(),
    val presentation: SourcePresentation = SourcePresentation(),
) {
    fun descriptor() = SourceDescriptor(id, name, SourceKind.SUPPLEMENTAL, versionCode, versionName,
        setOf(SourceCapability.HOME, SourceCapability.CONTENT, SourceCapability.CHILD_ITEMS,
            SourceCapability.IMPORT, SourceCapability.PLAYBACK) + extraCapabilities,
        useCustomPages = false, requiresLogin = requiresLogin, permissions = permissions, presentation = presentation)
}

/** 只需实现解析和取源；复杂服务、音质及页面仍可逐项替换，不改变旧 SourceEntry。 */
abstract class SimpleSupplementalSourceEntry(info: SimpleSupplementalSourceInfo) : SourceEntry, SourceSupplementalPageProvider {
    final override val descriptor = info.descriptor()
    final override val implementedCapabilities get() = descriptor.capabilities
    protected lateinit var environment: SourceEnvironment
        private set
    private val resolvedInputs = SimpleSupplementalContents(descriptor.id, ::parseInput)

    protected abstract suspend fun parseInput(input: String): List<SourceTrackDetails>
    protected abstract suspend fun resolvePlayback(request: SourcePlaybackRequest): SourcePlaybackResource
    protected open fun onInitialize(environment: SourceEnvironment) {}
    protected open fun onClose() {}

    /** 默认单档不推测编码或无损规格，多档由音源提供稳定标识及明确回退顺序。 */
    protected open val qualityDefinitions: List<SourceQualityDefinition> = listOf(SourceQualityDefinition("default", "默认音质", "默认"))
    protected open val defaultQualityId: String get() = qualityDefinitions.first().id
    protected open fun fallbackQualityIds(preferredId: String): List<String> = listOf(preferredId)
    protected open suspend fun recoverPlayback(request: SourcePlaybackRequest, previous: SourcePlaybackResource): SourcePlaybackResource = resolvePlayback(request)

    override val contents: SourceContents get() = resolvedInputs
    override val childItems: SourceChildItems get() = resolvedInputs
    override val importing: SourceImporting get() = resolvedInputs
    override val mediaPlayback: SourceMediaPlayback = object : SourceMediaPlayback, SourceQualityCatalog {
        override val qualityCatalog get() = qualityDefinitions
        override val defaultCatalogQualityId get() = this@SimpleSupplementalSourceEntry.defaultQualityId
        override val defaultQualityId get() = defaultCatalogQualityId
        override fun catalogFallbackQualityIds(preferredId: String) = this@SimpleSupplementalSourceEntry.fallbackQualityIds(preferredId)
        override fun fallbackQualityIds(preferredId: String) = catalogFallbackQualityIds(preferredId)
        override suspend fun qualities(track: SourceTrackDetails): List<SourceAudioQuality> =
            track.qualities.ifEmpty { qualityCatalog.map { SourceAudioQuality(it.id, it.label) } }
        override suspend fun resolve(request: SourcePlaybackRequest) = resolvePlayback(request)
        override suspend fun recover(request: SourcePlaybackRequest, previous: SourcePlaybackResource) = recoverPlayback(request, previous)
        override suspend fun availability(tracks: List<SourceTrackDetails>) = tracks.map { it.access }
    }

    final override fun initialize(environment: SourceEnvironment) {
        check(!::environment.isInitialized) { "音源已经初始化" }
        this.environment = environment
        onInitialize(environment)
    }
    final override fun close() { try { onClose() } finally { resolvedInputs.clear() } }
    @Composable override fun Content(route: SourceRoute) {}
    @Composable override fun SupplementalPage(context: SourceSupplementalPageContext) { context.PublicContent() }
}
