package com.musicone.source.sdk

data class SourceHomeSection(val id: String, val title: String, val tracks: List<SourceTrackDetails> = emptyList(),
    val collections: List<SourceCollection> = emptyList(), val entities: List<SourceEntity> = emptyList(),
    val payload: SourcePayload = SourcePayload())
data class SourceRecentSnapshot(val tracks: List<SourceTrackDetails> = emptyList(), val totalTracks: Int = tracks.size,
    val collections: List<SourceCollection> = emptyList(), val totalCollections: Int = collections.size,
    val albums: List<SourceCollection> = emptyList(), val totalAlbums: Int = albums.size,
    val radios: List<SourceCollection> = emptyList())

/** 可选的独立每日推荐入口；旧音源不实现此接口时不改变其原有二进制接口。 */
interface SourceDailyRecommendations {
    /** 返回本次完整推荐列表，数量由音源决定；公共模板不额外截断。 */
    suspend fun dailyRecommendations(policy: SourceFetchPolicy): List<SourceTrackDetails>
}

/** 音源声明容器身份和跨页合并规则，宿主不解释平台私有货架编号。 */
data class SourceHomePresentation(val stableId: String, val mergePages: Boolean = false,
    val recommendation: Boolean = false, val groupId: String? = null, val groupLimit: Int? = null)
interface SourceHomeSectionPresentation {
    fun homePresentation(section: SourceHomeSection): SourceHomePresentation
}

interface SourceDiscovery {
    suspend fun home(request: SourceCursorRequest): SourcePage<SourceHomeSection>
    val supportsSimilar: Boolean get() = false
    val supportsRecent: Boolean get() = false
    suspend fun similar(track: SourceTrackDetails, request: SourceCursorRequest): SourcePage<SourceTrackDetails> = throw UnsupportedOperationException("不支持相似推荐")
    suspend fun similarSection(track: SourceTrackDetails, request: SourceCursorRequest): SourceHomeSection =
        SourceHomeSection("similar", "相似推荐", tracks = similar(track, request).items)
    suspend fun recent(request: SourceCursorRequest): SourcePage<SourceTrackDetails> = throw UnsupportedOperationException("不支持最近播放")
    suspend fun recentSnapshot(): SourceRecentSnapshot = SourceRecentSnapshot(tracks = recent(SourceCursorRequest()).items)
    suspend fun reportPlayed(track: SourceTrackDetails, playedMs: Long) {}
}
enum class SourceSearchKind { TRACK, COLLECTION, ARTIST, ALBUM, CONTENT }
data class SourceSearchResult(val tracks: List<SourceTrackDetails> = emptyList(),
    val collections: List<SourceCollection> = emptyList(), val entities: List<SourceEntity> = emptyList(),
    val contents: List<SourceContent> = emptyList(), val nextCursor: String? = null)
interface SourceSearch {
    val kinds: Set<SourceSearchKind>
    suspend fun search(query: String, kind: SourceSearchKind, request: SourceCursorRequest): SourceSearchResult
    val supportsSuggestions: Boolean get() = false
    suspend fun suggestions(query: String): List<String> = throw UnsupportedOperationException("不支持搜索建议")
}
interface SourceEntities {
    val kinds: Set<String>
    fun supportsTrackSearch(reference: SourceEntityRef): Boolean = false
    fun trackSortOptions(reference: SourceEntityRef): List<SourceCollectionSortOption> = emptyList()
    suspend fun searchTracks(reference: SourceEntityRef, query: String, sortId: String?, request: SourceCursorRequest): SourcePage<SourceTrackDetails> =
        throw UnsupportedOperationException("不支持实体内搜索")
    suspend fun entity(reference: SourceEntityRef): SourceEntity
    suspend fun tracks(reference: SourceEntityRef, request: SourceCursorRequest): SourcePage<SourceTrackDetails>
    suspend fun collections(reference: SourceEntityRef, request: SourceCursorRequest): SourcePage<SourceCollection>
}
enum class SourceLibrarySection { FAVORITE_TRACKS, CREATED_COLLECTIONS, SAVED_COLLECTIONS }
data class SourceCollectionSortOption(val id: String, val label: String)
interface SourceCollections {
    fun sortOptions(collection: SourceCollection): List<SourceCollectionSortOption> = emptyList()
    fun selectedSort(collection: SourceCollection): String? = null
    fun selectSort(collection: SourceCollection, optionId: String) { error("此音源未提供歌单排序") }
    val librarySections: Set<SourceLibrarySection> get() = emptySet()
    suspend fun library(section: SourceLibrarySection, request: SourceCursorRequest): SourcePage<SourceCollection> = throw UnsupportedOperationException("不支持此资料库分区")
    suspend fun detail(collection: SourceCollection): SourceCollection
    suspend fun tracks(collection: SourceCollection, request: SourceCursorRequest): SourcePage<SourceTrackDetails>
}
interface SourceFavorites {
    val supportsCollections: Boolean get() = false
    suspend fun favoriteTrackIds(): Set<String>? = null
    suspend fun isTrackFavorite(track: SourceTrackDetails): Boolean
    suspend fun setTrackFavorite(track: SourceTrackDetails, favorite: Boolean)
    suspend fun setTracksFavorite(tracks: List<SourceTrackDetails>, favorite: Boolean) {
        tracks.forEach { setTrackFavorite(it, favorite) }
    }
    suspend fun setCollectionFavorite(collection: SourceCollection, favorite: Boolean): Unit =
        throw UnsupportedOperationException("不支持收藏歌单")
}
data class SourceCollectionEdit(val title: String, val description: String = "", val artworkUri: String? = null)
enum class SourceCollectionOperation { CREATE, EDIT, DELETE, CHANGE_TRACKS, REORDER }
interface SourceCollectionEditing {
    val supportedOperations: Set<SourceCollectionOperation> get() = emptySet()
    suspend fun writableCollections(): List<SourceCollection>
    suspend fun create(edit: SourceCollectionEdit): SourceCollection = throw UnsupportedOperationException("不支持创建歌单")
    suspend fun edit(collection: SourceCollection, edit: SourceCollectionEdit): SourceCollection = throw UnsupportedOperationException("不支持编辑歌单")
    suspend fun delete(collection: SourceCollection): Unit = throw UnsupportedOperationException("不支持删除歌单")
    suspend fun changeTracks(collection: SourceCollection, tracks: List<SourceTrackDetails>, add: Boolean): Unit = throw UnsupportedOperationException("不支持编辑歌单曲目")
    suspend fun reorder(collection: SourceCollection, orderedTrackIds: List<String>): Unit = throw UnsupportedOperationException("不支持远端排序")
}
data class SourceRadioBatch(val tracks: List<SourceTrackDetails>, val continuation: SourcePayload = SourcePayload())
interface SourceRadio {
    suspend fun next(previous: SourcePayload?): SourceRadioBatch
    suspend fun feedback(track: SourceTrackDetails, action: String)
}
