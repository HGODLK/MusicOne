package com.musicone.source.runtime

import com.musicone.source.sdk.*

object SourceCapabilityValidator {
    fun check(entry: SourceEntry) {
        val descriptor = entry.descriptor
        // 可选 getter 同样可能引用共享 ABI；在隔离加载阶段解析，不能等宿主绘制时才崩溃。
        listOf(entry.visuals, entry.pageState, entry.playback, entry.catalog, entry.authentication,
            entry.authenticationPage, entry.discovery, entry.search, entry.entities, entry.collections,
            entry.favorites, entry.collectionEditing, entry.radio, entry.contents, entry.childItems,
            entry.remoteCollections, entry.importing, entry.subscriptions, entry.lyricProvider,
            entry.trackTheme, entry.mediaPlayback, entry.settings)
        require(entry.implementedCapabilities.containsAll(descriptor.capabilities)) { "声明能力没有完整实现" }
        descriptor.capabilities.forEach { capability ->
            val available = when (capability) {
                SourceCapability.PLAYBACK -> entry.mediaPlayback != null || (entry.supplementalPageProviderOrNull() == null && entry.playback != null)
                SourceCapability.HOME -> entry.discovery != null || entry.catalog != null || descriptor.useCustomPages ||
                    (descriptor.kind == SourceKind.SUPPLEMENTAL && (entry.remoteCollections != null || entry.supplementalPageProviderOrNull() != null))
                SourceCapability.SEARCH -> entry.search?.kinds?.isNotEmpty() == true
                SourceCapability.ACCOUNT -> entry.authentication != null
                SourceCapability.PLAYLIST -> entry.collections != null
                SourceCapability.ARTIST -> entry.entities?.kinds?.contains("artist") == true
                SourceCapability.ALBUM -> entry.entities?.kinds?.contains("album") == true
                SourceCapability.RADIO -> entry.radio != null
                SourceCapability.CONTENT -> entry.contents != null
                SourceCapability.CHILD_ITEMS -> entry.childItems != null
                SourceCapability.REMOTE_COLLECTIONS -> entry.remoteCollections != null
                SourceCapability.IMPORT -> entry.importing != null || (entry.supplementalPageProviderOrNull() != null && (entry.contents != null || entry.remoteCollections != null))
                SourceCapability.SUBSCRIPTION -> entry.subscriptions != null
                SourceCapability.LYRICS -> entry.lyricProvider != null
                SourceCapability.SETTINGS -> entry.settings != null
            }
            require(available) { "能力 $capability 缺少对应真实服务接口" }
        }
        require(!descriptor.requiresLogin || entry.authentication != null) { "需要登录的包缺少认证服务" }
    }
}
