package com.musicone.source.runtime

import com.musicone.source.sdk.*

/** 保留可选能力的真实存在性；旧包不能被包装成具有独立每日推荐的新包。 */
internal fun guardSourceDiscovery(service: SourceDiscovery, operations: SourceOperationLease): SourceDiscovery {
    val guarded = object : SourceDiscovery {
        override val supportsSimilar get() = operations.read { service.supportsSimilar }
        override val supportsRecent get() = operations.read { service.supportsRecent }
        override suspend fun home(request: SourceCursorRequest) = operations.call { service.home(request) }
        override suspend fun similar(track: SourceTrackDetails, request: SourceCursorRequest) = operations.call { service.similar(track, request) }
        override suspend fun similarSection(track: SourceTrackDetails, request: SourceCursorRequest) = operations.call { service.similarSection(track, request) }
        override suspend fun recent(request: SourceCursorRequest) = operations.call { service.recent(request) }
        override suspend fun recentSnapshot() = operations.call { service.recentSnapshot() }
        override suspend fun reportPlayed(track: SourceTrackDetails, playedMs: Long) = operations.call { service.reportPlayed(track, playedMs) }
    }
    val daily = (service as? SourceDailyRecommendations)?.let { original -> object : SourceDailyRecommendations {
        override suspend fun dailyRecommendations(policy: SourceFetchPolicy) = operations.call { original.dailyRecommendations(policy) }
    } }
    val layout = (service as? SourceHomeSectionPresentation)?.let { original -> object : SourceHomeSectionPresentation {
        override fun homePresentation(section: SourceHomeSection) = operations.read { original.homePresentation(section) }
    } }
    val continuation = (service as? SourceHomeContinuation)?.let { original -> object : SourceHomeContinuation {
        override fun hasBufferedHomeItems(cursor: String) = operations.read { original.hasBufferedHomeItems(cursor) }
    } }
    val my = (service as? SourceMySectionPresentation)?.let { original -> object : SourceMySectionPresentation {
        override val showRecentInMy get() = operations.read { original.showRecentInMy }
    } }
    return when {
        my != null && daily != null && layout != null && continuation != null -> object : SourceDiscovery by guarded, SourceDailyRecommendations by daily, SourceHomeSectionPresentation by layout, SourceHomeContinuation by continuation, SourceMySectionPresentation by my {}
        my != null && daily != null && continuation != null -> object : SourceDiscovery by guarded, SourceDailyRecommendations by daily, SourceHomeContinuation by continuation, SourceMySectionPresentation by my {}
        my != null && layout != null && continuation != null -> object : SourceDiscovery by guarded, SourceHomeSectionPresentation by layout, SourceHomeContinuation by continuation, SourceMySectionPresentation by my {}
        my != null && continuation != null -> object : SourceDiscovery by guarded, SourceHomeContinuation by continuation, SourceMySectionPresentation by my {}
        my != null && daily != null && layout != null -> object : SourceDiscovery by guarded, SourceDailyRecommendations by daily, SourceHomeSectionPresentation by layout, SourceMySectionPresentation by my {}
        my != null && daily != null -> object : SourceDiscovery by guarded, SourceDailyRecommendations by daily, SourceMySectionPresentation by my {}
        my != null && layout != null -> object : SourceDiscovery by guarded, SourceHomeSectionPresentation by layout, SourceMySectionPresentation by my {}
        my != null -> object : SourceDiscovery by guarded, SourceMySectionPresentation by my {}
        daily != null && layout != null && continuation != null -> object : SourceDiscovery by guarded, SourceDailyRecommendations by daily, SourceHomeSectionPresentation by layout, SourceHomeContinuation by continuation {}
        daily != null && continuation != null -> object : SourceDiscovery by guarded, SourceDailyRecommendations by daily, SourceHomeContinuation by continuation {}
        layout != null && continuation != null -> object : SourceDiscovery by guarded, SourceHomeSectionPresentation by layout, SourceHomeContinuation by continuation {}
        continuation != null -> object : SourceDiscovery by guarded, SourceHomeContinuation by continuation {}
        daily != null && layout != null -> object : SourceDiscovery by guarded, SourceDailyRecommendations by daily, SourceHomeSectionPresentation by layout {}
        daily != null -> object : SourceDiscovery by guarded, SourceDailyRecommendations by daily {}
        layout != null -> object : SourceDiscovery by guarded, SourceHomeSectionPresentation by layout {}
        else -> guarded
    }
}
