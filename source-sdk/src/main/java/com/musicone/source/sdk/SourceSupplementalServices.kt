package com.musicone.source.sdk

data class SourceContent(val identity: TrackIdentity, val title: String, val description: String = "",
    val author: SourceEntityRef? = null, val artwork: SourceArtwork = SourceArtwork(),
    val durationMs: Long = 0, val access: TrackAccessInfo = TrackAccessInfo(), val payload: SourcePayload = SourcePayload(),
    val playableTrack: SourceTrackDetails? = null)
data class SourceChildItem(val identity: TrackIdentity, val index: Int, val title: String,
    val durationMs: Long = 0, val payload: SourcePayload = SourcePayload(), val initiallySelected: Boolean = false)
interface SourceContents {
    suspend fun resolve(input: String): SourceContent
    suspend fun detail(content: SourceContent): SourceContent
}
interface SourceChildItems {
    suspend fun children(content: SourceContent, request: SourceCursorRequest): SourcePage<SourceChildItem>
}
data class SourceRemoteCollection(val identity: TrackIdentity, val title: String, val count: Int? = null,
    val artwork: SourceArtwork = SourceArtwork(), val payload: SourcePayload = SourcePayload())
interface SourceRemoteCollections {
    suspend fun collections(request: SourceCursorRequest): SourcePage<SourceRemoteCollection>
    suspend fun contents(collection: SourceRemoteCollection, query: String?, request: SourceCursorRequest): SourcePage<SourceContent>
}
/** 可选集合封面能力；宿主只为可见卡片请求，失败或空图时保留已显示封面。 */
interface SourceRemoteCollectionArtwork {
    suspend fun artwork(collection: SourceRemoteCollection, policy: SourceFetchPolicy): SourceArtwork
}
interface SourceImporting {
    suspend fun tracks(content: SourceContent, selectedChildren: List<SourceChildItem>): List<SourceTrackDetails>
}
data class SourceSubscriptionIdentity(val sourceId: String, val accountId: String,
    val remoteCollectionId: String, val localCollectionId: String)
data class SourceSubscriptionItem(val remoteMemberId: String, val tracks: List<SourceTrackDetails>, val position: Long)
interface SourceSubscriptions {
    // 宿主收齐全部成功分页后原子提交；失败或取消保留旧内容、手动成员和资料覆盖。
    suspend fun page(collection: SourceRemoteCollection, request: SourceCursorRequest): SourcePage<SourceSubscriptionItem>
}
