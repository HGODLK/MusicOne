package com.musicone.source.runtime

import com.musicone.source.sdk.*

/** 目录能力只对主动实现的包公开；旧包仍使用原播放与展示默认值。 */
internal fun guardSourceMediaPlayback(service: SourceMediaPlayback, operations: SourceOperationLease): SourceMediaPlayback {
    val guarded = object : SourceMediaPlayback, SourcePlaybackBehavior, SourcePlayerPresentation, SourcePlaybackSessionProvider {
        override val playbackSessions get() = operations.read {
            (service as? SourcePlaybackSessionProvider)?.playbackSessions?.let { observer ->
                // 捕获取得回调时的账号，排队事件不能在换号后绑定到新账号。
                val checkOwner = operations.deferredCheck()
                SourcePlaybackSessionObserver { event ->
                    checkOwner()
                    operations.call { checkOwner(); observer.onPlaybackSession(event) }
                }
            }
        }
        override fun qualityLabel(qualityId: String) = operations.read { (service as? SourcePlayerPresentation)?.qualityLabel(qualityId) }
        override fun qualityCompactLabel(qualityId: String) = operations.read { (service as? SourcePlayerPresentation)?.qualityCompactLabel(qualityId) }
        override val pendingAudioDescription get() = operations.read { (service as? SourcePlayerPresentation)?.pendingAudioDescription }
        override val missingArtworkHoldMs get() = operations.read { (service as? SourcePlayerPresentation)?.missingArtworkHoldMs ?: 0L }
        override val renewOnPlayerErrorLimit get() = operations.read { (service as? SourcePlaybackBehavior)?.renewOnPlayerErrorLimit ?: 0 }
        override val skipOnPlayerError get() = operations.read { (service as? SourcePlaybackBehavior)?.skipOnPlayerError ?: true }
        override val pauseOnFailure get() = operations.read { (service as? SourcePlaybackBehavior)?.pauseOnFailure ?: false }
        override val defaultQualityId get() = operations.read { service.defaultQualityId }
        override val followsMainQualityPreference get() = operations.read { service.followsMainQualityPreference }
        override fun normalizePreferredQuality(savedQualityId: String?, mainQualityId: String?) = operations.read { service.normalizePreferredQuality(savedQualityId, mainQualityId) }
        override fun fallbackQualityIds(preferredId: String) = operations.read { service.fallbackQualityIds(preferredId) }
        override suspend fun enrich(track: SourceTrackDetails) = operations.call { service.enrich(track) }
        override suspend fun qualities(track: SourceTrackDetails) = operations.call { service.qualities(track) }
        override suspend fun qualities(track: SourceTrackDetails, onProgress: (List<SourceAudioQuality>) -> Unit) = operations.call {
            // 账号或包失效后的中途结果直接丢弃，最终结果仍由 call 统一校验。
            val current = operations.deferredCheck()
            val forward: (List<SourceAudioQuality>) -> Unit = { rows -> if (runCatching(current).isSuccess) onProgress(rows) }
            try {
                service.qualities(track, forward)
            } catch (missing: AbstractMethodError) {
                // 新增重载前编译的旧包没有这个方法，退回一次性返回。
                service.qualities(track).also(forward)
            }
        }
        override suspend fun resolve(request: SourcePlaybackRequest) = operations.call { service.resolve(request) }
        override suspend fun recover(request: SourcePlaybackRequest, previous: SourcePlaybackResource) = operations.call { service.recover(request, previous) }
        override suspend fun availability(tracks: List<SourceTrackDetails>) = operations.call { service.availability(tracks) }
    }
    return if (service is SourceQualityCatalog) object : SourceMediaPlayback by guarded,
        SourcePlaybackBehavior by guarded, SourcePlayerPresentation by guarded,
        SourcePlaybackSessionProvider by guarded, SourceQualityCatalog {
        override val qualityCatalog get() = operations.read { service.qualityCatalog }
        override val defaultCatalogQualityId get() = operations.read { service.defaultCatalogQualityId }
        override fun catalogFallbackQualityIds(preferredId: String) = operations.read { service.catalogFallbackQualityIds(preferredId) }
    } else guarded
}
