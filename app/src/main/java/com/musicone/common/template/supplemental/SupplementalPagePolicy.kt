package com.musicone

import com.musicone.source.sdk.*

internal fun SourceSupplementalPage.internalPage(): SupplementalPage = when (this) {
    SourceSupplementalPage.HOME -> SupplementalPage.HOME
    SourceSupplementalPage.ACCOUNT -> SupplementalPage.ACCOUNT
    SourceSupplementalPage.LINK -> SupplementalPage.LINK
    SourceSupplementalPage.COLLECTIONS -> SupplementalPage.FOLDERS
    SourceSupplementalPage.COLLECTION -> SupplementalPage.FOLDER
    SourceSupplementalPage.CONTENT -> SupplementalPage.CONTENT
    SourceSupplementalPage.SETTINGS -> SupplementalPage.SETTINGS
}
internal fun SupplementalPage.publicPage(): SourceSupplementalPage = SourceSupplementalPage.entries.first { it.internalPage() == this }

/** 自定义页可自行提供能力，公共页仅在存在对应业务服务时开放。 */
internal fun supplementalPageAvailable(entry: SourceEntry, page: SourceSupplementalPage): Boolean {
    val provider = entry.supplementalPageProviderOrNull() ?: return true
    return when (provider.supplementalPagePolicy(page).mode) {
        SourceSupplementalPageMode.DISABLED -> false
        SourceSupplementalPageMode.CUSTOM -> true
        SourceSupplementalPageMode.DEFAULT -> supplementalPublicPageAvailable(entry, page)
    }
}
internal fun supplementalPublicPageAvailable(entry: SourceEntry, page: SourceSupplementalPage): Boolean = when (page) {
    SourceSupplementalPage.HOME -> true
    SourceSupplementalPage.LINK -> entry.contents != null
    SourceSupplementalPage.CONTENT -> entry.contents != null || entry.remoteCollections != null || entry.importing != null
    SourceSupplementalPage.COLLECTIONS, SourceSupplementalPage.COLLECTION -> entry.remoteCollections != null
    SourceSupplementalPage.ACCOUNT -> entry.authentication != null
    SourceSupplementalPage.SETTINGS -> entry.settings != null
}

/** 新契约详情、列表和批量转换共用规则；旧包继续强制通过原导入服务。 */
internal suspend fun supplementalTracks(entry: SourceEntry, content: SourceContent,
    selected: List<SourceChildItem>): List<SourceTrackDetails> {
    val modern = entry.supplementalPageProviderOrNull() != null
    if (modern) require(content.identity.sourceId == entry.descriptor.id) { "内容不属于当前音源" }
    val detailed = if (modern && selected.isEmpty() && content.playableTrack != null)
        entry.contents?.detail(content) ?: content else content
    return supplementalPreparedTracks(entry, detailed, selected)
}

private suspend fun supplementalPreparedTracks(entry: SourceEntry, content: SourceContent,
    selected: List<SourceChildItem>): List<SourceTrackDetails> {
    val modern = entry.supplementalPageProviderOrNull() != null
    if (modern) require(content.identity.sourceId == entry.descriptor.id) { "内容不属于当前音源" }
    val directTrack = content.playableTrack
    val tracks = if (modern && selected.isEmpty() && directTrack != null) listOf(directTrack)
        else checkNotNull(entry.importing) { "此音源未提供导入" }.tracks(content, selected)
    if (modern) require(tracks.all { it.track.identity.sourceId == entry.descriptor.id }) { "歌曲不属于当前音源" }
    return tracks
}

/** 自动处理整项内容时遵循音源默认子项目选择，不能把未选择的子项目静默全选。 */
internal suspend fun supplementalDefaultTracks(entry: SourceEntry, content: SourceContent): List<SourceTrackDetails> {
    // 新契约以详情服务的最新结果为准，重解析后单曲和多子项目之间也可能切换。
    val detailed = entry.contents?.detail(content) ?: content
    val children = entry.childItems?.let { service -> collectPages { cursor -> service.children(detailed, SourceCursorRequest(cursor)) } }.orEmpty()
    val selected = children.filter { it.initiallySelected }
    require(children.isEmpty() || selected.isNotEmpty()) { "请先打开“${detailed.title}”选择子项目" }
    return supplementalPreparedTracks(entry, detailed, selected)
}
