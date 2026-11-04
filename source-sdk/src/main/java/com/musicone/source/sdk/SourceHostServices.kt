package com.musicone.source.sdk

data class SourceLocalCollection(val id: String, val title: String, val acceptsManualTracks: Boolean)
// null 保持现有覆盖，空字符串恢复原资料。
data class SourceTrackMetadataEdit(val title: String? = null, val artists: String? = null, val album: String? = null,
    val artworkUri: String? = null)
interface SourceLibraryHost {
    suspend fun createCollection(title: String): SourceLocalCollection = throw UnsupportedOperationException("宿主未提供新建歌单")
    suspend fun destinations(): List<SourceLocalCollection>
    suspend fun importTracks(tracks: List<SourceTrackDetails>, destinationId: String)
    suspend fun editMetadata(track: SourceTrackDetails, edit: SourceTrackMetadataEdit)
    suspend fun setManualLyrics(track: SourceTrackDetails, documentUri: String?)
    // 用户输入正数表示延后，不改变包的默认整行／逐字提前量。
    suspend fun setLineOffset(track: SourceTrackDetails, offsetMs: Long)
    suspend fun lineOffset(track: SourceTrackDetails): Long = 0
    suspend fun subscriptionRevision(identity: SourceSubscriptionIdentity): Long
    suspend fun commitSubscription(identity: SourceSubscriptionIdentity, expectedRevision: Long, items: List<SourceSubscriptionItem>)
    suspend fun removeSubscription(identity: SourceSubscriptionIdentity, keepTracks: Boolean)
}
data class SourceCacheEntry(val stableTrackId: String, val complete: Boolean, val mediaUri: String?,
    val mimeType: String?, val recovery: SourcePayload = SourcePayload())
interface SourceCacheHost {
    suspend fun lookup(track: SourceTrackDetails, qualityId: String?): SourceCacheEntry?
    suspend fun download(track: SourceTrackDetails, resource: SourcePlaybackResource)
    suspend fun cancel(track: SourceTrackDetails)
}
data class SourceUiRequest(val kind: String, val arguments: Map<String, String> = emptyMap())
data class SourceUiResult(val cancelled: Boolean, val values: Map<String, String> = emptyMap())
interface SourceUiHost {
    // 文档选择、封面选择／裁剪、外部验证页等由宿主实现；取消应返回 cancelled=true。
    suspend fun request(request: SourceUiRequest): SourceUiResult
    fun openDestination(destination: String, arguments: Map<String, String> = emptyMap())
}
interface SourceSettings { @androidx.compose.runtime.Composable fun Content() }
interface SourceAuthenticationPage { @androidx.compose.runtime.Composable fun Content() }
