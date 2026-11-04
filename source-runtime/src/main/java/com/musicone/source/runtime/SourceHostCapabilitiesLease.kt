package com.musicone.source.runtime

import com.musicone.source.sdk.*

internal class SourceHostCapabilitiesLease(host: SourceHost, private val descriptor: SourceDescriptor,
    private val operations: SourceOperationLease) {
    private fun verifyTrack(track: SourceTrackDetails) { require(track.track.identity.sourceId == descriptor.id) { "不能跨音源写入资料" } }
    private fun subscription(identity: SourceSubscriptionIdentity) { require(identity.sourceId == descriptor.id) { "不能跨音源写入订阅" } }
    val library: SourceLibraryHost? = host.library?.let { service -> object : SourceLibraryHost {
        override suspend fun createCollection(title: String) = operations.call { service.createCollection(title) }
        override suspend fun destinations() = operations.call { service.destinations() }
        override suspend fun importTracks(tracks: List<SourceTrackDetails>, destinationId: String) = operations.call {
            tracks.forEach(::verifyTrack); service.importTracks(tracks, destinationId)
        }
        override suspend fun editMetadata(track: SourceTrackDetails, edit: SourceTrackMetadataEdit) = operations.call {
            verifyTrack(track); check(descriptor.permissions.editTrackMetadata) { "音源未授权资料编辑" }; service.editMetadata(track, edit)
        }
        override suspend fun setManualLyrics(track: SourceTrackDetails, documentUri: String?) = operations.call {
            verifyTrack(track); check(descriptor.permissions.replaceManualLyrics) { "音源未授权手动歌词替换" }; service.setManualLyrics(track, documentUri)
        }
        override suspend fun setLineOffset(track: SourceTrackDetails, offsetMs: Long) = operations.call {
            verifyTrack(track); check(descriptor.permissions.adjustLineOffset) { "音源未授权整行偏移" }; service.setLineOffset(track, offsetMs)
        }
        override suspend fun lineOffset(track: SourceTrackDetails) = operations.call { verifyTrack(track); service.lineOffset(track) }
        override suspend fun subscriptionRevision(identity: SourceSubscriptionIdentity) = operations.call {
            subscription(identity); service.subscriptionRevision(identity)
        }
        override suspend fun commitSubscription(identity: SourceSubscriptionIdentity, expectedRevision: Long, items: List<SourceSubscriptionItem>) = operations.call {
            subscription(identity); items.flatMap { it.tracks }.forEach(::verifyTrack)
            service.commitSubscription(identity, expectedRevision, items)
        }
        override suspend fun removeSubscription(identity: SourceSubscriptionIdentity, keepTracks: Boolean) = operations.call {
            subscription(identity); service.removeSubscription(identity, keepTracks)
        }
    } }
    val cache: SourceCacheHost? = host.cache?.let { service -> object : SourceCacheHost {
        override suspend fun lookup(track: SourceTrackDetails, qualityId: String?) = operations.call { verifyTrack(track); service.lookup(track, qualityId) }
        override suspend fun download(track: SourceTrackDetails, resource: SourcePlaybackResource) = operations.call { verifyTrack(track); service.download(track, resource) }
        override suspend fun cancel(track: SourceTrackDetails) = operations.call { verifyTrack(track); service.cancel(track) }
    } }
    val ui: SourceUiHost? = host.ui?.let { service -> object : SourceUiHost {
        override suspend fun request(request: SourceUiRequest) = operations.call { service.request(request) }
        override fun openDestination(destination: String, arguments: Map<String, String>) = operations.dispatch { service.openDestination(destination, arguments) }
    } }
}

