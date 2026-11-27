package com.musicone.source.sdk

import kotlinx.coroutines.flow.Flow

/** 可选的资料库扩展能力，兼容旧音源接口和原有分页行为。 */
interface SourceCollectionFavoritePolicy {
    fun canFavorite(collection: SourceCollection): Boolean = true
    /** 同一账号下用于匹配同一首歌的收藏键；宿主只比对交集，不解析平台 ID。 */
    fun favoriteIdentityKeys(collection: SourceCollection): Set<String> = setOf(collection.stableId)
}

data class SourceLibraryChange(val accountId: String, val sections: Set<SourceLibrarySection>)
interface SourceLibraryChanges { val libraryChanges: Flow<SourceLibraryChange> }

enum class SourceCollectionEditField { DESCRIPTION, ARTWORK }
/** description=null 表示保留，空字符串明确表示清空；artworkUri=null 表示保留封面。 */
data class SourceCollectionDetailsEdit(val title: String, val description: String? = null, val artworkUri: String? = null)
interface SourceCollectionDetailsEditing {
    val editableFields: Set<SourceCollectionEditField>
    suspend fun createDetails(edit: SourceCollectionDetailsEdit): SourceCollection
    suspend fun editDetails(collection: SourceCollection, edit: SourceCollectionDetailsEdit): SourceCollection
}

/** 调整云端歌单顺序，不影响本地排序和播放队列；读取时需拉完所有分页并保持服务端顺序。 */
interface SourceCollectionRemoteOrder {
    fun supportsRemoteOrder(collection: SourceCollection): Boolean
    suspend fun orderedTracks(collection: SourceCollection, request: SourceCursorRequest): SourcePage<SourceTrackDetails>
}

/**
 * 歌单归属：由 [SourceCollectionEditing] 的实现对象一并实现。宿主“添加到歌单”先用本地结果立即显示，再联网核对。
 * 未实现时宿主回退为逐页读取歌单内容判断，行为与旧包一致。
 */
interface SourceCollectionMembership {
    val supportsMembership: Boolean get() = true
    /** 本地已知的可写歌单，只读本地不联网；没有可用缓存返回 null。 */
    suspend fun cachedWritableCollections(): List<SourceCollection>?
    /**
     * 返回 [tracks] 中已在 [collection] 内的曲目 `stableId`。
     * CACHE_FIRST 只读本地索引，没有索引返回 null，不能联网；REFRESH 按需联网核对并返回确定结果，失败抛异常。
     */
    suspend fun containedTracks(collection: SourceCollection, tracks: List<SourceTrackDetails>, policy: SourceFetchPolicy): Set<String>?
}

interface SourceEntityFollowing {
    val supportsFollowing: Boolean get() = true
    suspend fun isFollowing(reference: SourceEntityRef): Boolean
    suspend fun setFollowing(reference: SourceEntityRef, following: Boolean)
    suspend fun followed(request: SourceCursorRequest): SourcePage<SourceEntity>
}

interface SourceHotSearch {
    val supportsHotSearch: Boolean get() = true
    suspend fun hotSearches(): List<String>
}
