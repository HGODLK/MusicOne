package com.musicone

import com.musicone.source.sdk.*

/** 新版补充音源只开放它明确声明且宿主能执行的操作，旧版包保持原有入口。 */
internal fun supplementalCanPlay(entry: SourceEntry): Boolean = entry.supplementalPageProviderOrNull() == null ||
    (SourceCapability.PLAYBACK in entry.descriptor.capabilities && entry.mediaPlayback != null)
internal fun supplementalCanImport(entry: SourceEntry, content: SourceContent? = null, hasChildren: Boolean = false): Boolean {
    if (entry.supplementalPageProviderOrNull() == null) return true
    if (SourceCapability.IMPORT !in entry.descriptor.capabilities) return false
    return if (content != null) supplementalCanConvert(entry, content, hasChildren)
        else entry.importing != null || entry.contents != null || entry.remoteCollections != null
}
internal fun supplementalCanConvert(entry: SourceEntry, content: SourceContent, hasChildren: Boolean = false): Boolean =
    entry.supplementalPageProviderOrNull() == null || entry.importing != null || (!hasChildren && content.playableTrack != null)
internal fun requireSupplementalAction(entry: SourceEntry, play: Boolean, contents: List<SourceContent> = emptyList()) {
    val canImport = supplementalCanImport(entry) || (SourceCapability.IMPORT in entry.descriptor.capabilities &&
        contents.isNotEmpty() && contents.all { supplementalCanConvert(entry, it) })
    check(if (play) supplementalCanPlay(entry) else canImport) {
        if (play) "此音源未提供可用的播放能力" else "此音源未提供可用的导入能力"
    }
}
