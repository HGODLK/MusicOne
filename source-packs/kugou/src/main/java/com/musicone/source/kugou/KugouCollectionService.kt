package com.musicone.source.kugou

import com.musicone.source.sdk.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 复用既有歌曲读取和公共歌单页面，仅适配酷狗账号下的云歌单管理。 */
internal class KugouCollectionService(
    private val state: () -> KugouPackageState,
    private val catalog: KugouSourceCatalog,
    private val client: KugouCollectionClient = KugouCollectionClient(),
    private val publicCollections: KugouPublicCollectionClient = KugouPublicCollectionClient(),
) : SourceCollections, SourceFavorites, SourceCollectionEditing, SourceCollectionCachePolicy, SourceCollectionReturnBehavior, SourceCollectionFavoritePolicy, SourceLibraryChanges {
    private val changes = kotlinx.coroutines.flow.MutableSharedFlow<SourceLibraryChange>(extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    override val libraryChanges = changes as kotlinx.coroutines.flow.Flow<SourceLibraryChange>
    private fun changed(owner: String) { changes.tryEmit(SourceLibraryChange(owner, SourceLibrarySection.entries.toSet())) }
    override fun canFavorite(collection: SourceCollection) = !collection.isKugouHomeCollection() && !collection.owned && !collection.accountFavorites
    override fun favoriteIdentityKeys(collection: SourceCollection) = kugouCollectionFavoriteKeys(collection)
    private val directory by lazy { KugouCollectionDirectory(java.io.File(state().environment.dataDirectory, "collection-directory-v1"), ::changed) }
    private val tracksCache = lazy { KugouCollectionTracks(java.io.File(state().environment.dataDirectory, "collection-tracks-v1")) }
    private val collectionTracks by tracksCache
    private val savedCollectionTracks = KugouSavedCollectionTracks(publicCollections)
    private val artworkPreparation = KugouCollectionArtworkPreparation()
    fun clearMemory() {
        artworkPreparation.clear()
        if (tracksCache.isInitialized()) collectionTracks.clearMemory()
    }
    private val sortOptions = listOf(SourceCollectionSortOption("ADDED_DESC", "加入时间：新到旧"),
        SourceCollectionSortOption("ADDED_ASC", "加入时间：旧到新"), SourceCollectionSortOption("NAME", "歌曲名称：升序"))
    private fun sortKey(collection: SourceCollection): String? {
        val account = state().account()?.userId ?: return null
        if (collection.identity.sourceId != KUGOU_SOURCE_ID ||
            collection.identity.accountId?.let { it != account } == true) return null
        return "kugou-sort:$account:${collection.stableId}"
    }
    override fun sortOptions(collection: SourceCollection) = if (sortKey(collection) == null) emptyList() else sortOptions
    override fun selectedSort(collection: SourceCollection): String? = sortKey(collection)?.let {
        state().preferences.getString(it, "ADDED_DESC")?.takeIf { value -> sortOptions.any { option -> option.id == value } }
            ?: "ADDED_DESC"
    }
    override fun selectSort(collection: SourceCollection, optionId: String) {
        require(sortOptions.any { it.id == optionId }) { "不支持此歌单排序" }
        val key = requireNotNull(sortKey(collection)) { "排序账号不匹配" }
        check(state().preferences.edit().putString(key, optionId).commit())
    }
    private val artwork by lazy { KugouCollectionArtworkStore(java.io.File(state().environment.dataDirectory, "collection-artwork-v1")) }
    override val refreshLibraryAfterAnyReturn = true
    override val librarySections = SourceLibrarySection.entries.toSet()
    override val supportedOperations = SourceCollectionOperation.entries.toSet()
    override val supportsCollections = true
    override fun includesInCacheLibrary(collection: SourceCollection) = isKugouAccountCollection(collection)

    override suspend fun library(section: SourceLibrarySection, request: SourceCursorRequest): SourcePage<SourceCollection> =
        state().request { captured ->
            require(request.cursor == null) { "酷狗云歌单目录已全部返回" }
            val guard = { state().check(captured) }
            val owner = captured.cookie.requiredKugouSession().userId
            // 首次展示先用账号缓存，目录按既有后台核对更新；主动刷新仍读取云端。
            val library = directory.read(owner, captured.generation, section, request.policy, guard,
                { client.library(captured.cookie, beforeWrite(captured)) }, { work ->
                    state().scope.launch(Dispatchers.IO) { work() }
                })
            val lists = when (section) {
                SourceLibrarySection.FAVORITE_TRACKS -> library.filter { it.playlist.isPlatformFavorites }
                SourceLibrarySection.CREATED_COLLECTIONS -> library.filter { it.created }
                SourceLibrarySection.SAVED_COLLECTIONS -> library.filter { it.savedPlaylist }
            }
            SourcePage(lists.map { withArtwork(it, captured) }, null)
        }

    override suspend fun detail(collection: SourceCollection): SourceCollection {
        if (collection.kugouGlobalCollectionId() != null) return state().request { captured ->
            requireAccount(collection, captured.cookie)
            publicCollections.detail(collection, captured.cookie)
        }
        if (collection.stableId.startsWith("kugou-cloudlist:")) return state().request { captured ->
            requireAccount(collection, captured.cookie)
            val target = accountDirectory(captured).firstOrNull { it.playlist.id == collection.stableId }
                ?: error("此云歌单已不存在，请刷新资料库")
            if (target.created) {
                val cover = trackSnapshot(captured, target).latestArtwork()
                mapped(target, captured).let { it.copy(artwork = it.artwork.copy(url = cover)) }
            } else withArtwork(target, captured)
        }
        val fresh = collection
        // 原解析负责曲目和封面；管理信息回填，避免公共映射丢失收藏来源编号。
        return catalog.detail(fresh).copy(owned = fresh.owned, accountFavorites = fresh.accountFavorites,
            payload = fresh.payload, description = fresh.kugouAlbumDetailDescription())
    }

    override suspend fun tracks(collection: SourceCollection, request: SourceCursorRequest): SourcePage<SourceTrackDetails> =
        if (collection.kugouGlobalCollectionId() != null) state().request { captured ->
            requireAccount(collection, captured.cookie)
            publicCollections.tracks(collection, request, captured.cookie, state().verifiedVipAccess(captured))
        } else state().request { captured ->
            requireAccount(collection, captured.cookie)
            if (collection.stableId.startsWith("kugou-cloudlist:")) {
                val target = accountDirectory(captured).firstOrNull { it.playlist.id == collection.stableId }
                    ?: error("此云歌单已不存在，请刷新资料库")
                val guard = beforeWrite(captured)
                val vip = state().verifiedVipAccess(captured)
                savedCollectionTracks.read(collection, target, request, captured.cookie, vip, guard) {
                    trackSnapshot(captured, target, if (request.cursor == null) request.policy else SourceFetchPolicy.CACHE_FIRST)
                        .page(target, vip, request, check = guard)
                }
            } else catalog.tracks(collection, request).withKugouAlbumCollectionMetadata(collection)
        }

    override suspend fun writableCollections(): List<SourceCollection> = state().request { captured ->
        accountDirectory(captured).filter { it.writable }.map { mapped(it, captured) }
    }

    override suspend fun create(edit: SourceCollectionEdit): SourceCollection = state().request { captured ->
        validateEdit(edit)
        val beforeIds = freshDirectory(captured).mapTo(mutableSetOf()) { it.listId }
        val id = writing(captured) { beforeWrite -> client.create(captured.cookie, edit.title.trim(), beforeWrite) }
        state().check(captured)
        var target = confirmKugouCreatedCollection {
            kugouCreatedCollection(beforeIds, freshDirectory(captured), edit.title.trim(), id)
        }
        if (edit.description.isNotBlank()) {
            currentCoroutineContext().ensureActive()
            state().check(captured)
            writing(captured) { beforeWrite -> client.edit(captured.cookie, target, edit.title.trim(), edit.description, beforeWrite) }
            target = target.copy(playlist = target.playlist.copy(description = edit.description))
        }
        mapped(target, captured)
    }

    override suspend fun edit(collection: SourceCollection, edit: SourceCollectionEdit): SourceCollection =
        state().request { captured ->
            validateEdit(edit)
            val target = writable(captured, collection)
            state().check(captured)
            writing(captured) { beforeWrite -> client.edit(captured.cookie, target, edit.title.trim(), edit.description, beforeWrite) }
            mapped(target.copy(playlist = target.playlist.copy(title = edit.title.trim(),
                description = edit.description.ifBlank { target.playlist.description })), captured)
        }

    override suspend fun delete(collection: SourceCollection): Unit = state().request { captured ->
        val target = writable(captured, collection)
        state().check(captured)
        writing(captured) { beforeWrite -> client.delete(captured.cookie, target, beforeWrite) }
    }

    override suspend fun changeTracks(collection: SourceCollection, tracks: List<SourceTrackDetails>, add: Boolean): Unit =
        state().request { captured ->
            val target = writable(captured, collection)
            val values = tracks.map(KugouSourceMapping::track)
            state().check(captured)
            writing(captured) { beforeWrite -> client.changeTracks(captured.cookie, target, values, add, beforeWrite) }
        }

    override suspend fun reorder(collection: SourceCollection, orderedTrackIds: List<String>): Unit = state().request { captured ->
        val target = writable(captured, collection)
        state().check(captured)
        writing(captured) { beforeWrite -> client.reorder(captured.cookie, target, orderedTrackIds, beforeWrite) }
    }

    override suspend fun favoriteTrackIds(): Set<String> = state().request { captured ->
        val target = favorites(captured, fresh = true)
        val ids = trackSnapshot(captured, target, SourceFetchPolicy.REFRESH).favoriteTrackIds(beforeWrite(captured))
        // 后台核对完成后让卡片读取已更新的快照，不用播放动作触发封面刷新。
        state().check(captured)
        changes.tryEmit(SourceLibraryChange(captured.cookie.requiredKugouSession().userId,
            setOf(SourceLibrarySection.FAVORITE_TRACKS)))
        ids
    }
    override suspend fun isTrackFavorite(track: SourceTrackDetails): Boolean {
        require(track.track.identity.sourceId == KUGOU_SOURCE_ID) { "此操作只接受酷狗歌曲" }
        val hashes = (KugouSourceMapping.track(track).qualityIds.values + track.track.identity.remoteId)
            .map { "kugou-${it.removePrefix("kugou-").lowercase()}" }.toSet()
        return favoriteTrackIds().any { it in hashes }
    }
    override suspend fun setTrackFavorite(track: SourceTrackDetails, favorite: Boolean): Unit =
        setTracksFavorite(listOf(track), favorite)
    override suspend fun setTracksFavorite(tracks: List<SourceTrackDetails>, favorite: Boolean): Unit = state().request { captured ->
        val values = tracks.map(KugouSourceMapping::track)
        val target = favorites(captured, fresh = true)
        state().check(captured)
        writing(captured) { beforeWrite -> client.changeTracks(captured.cookie, target, values, favorite, beforeWrite) }
    }

    override suspend fun setCollectionFavorite(collection: SourceCollection, favorite: Boolean): Unit = state().request { captured ->
        require(!collection.isKugouHomeCollection()) { "每日推荐与最近播放是只读列表，不能收藏为云歌单" }
        requireAccount(collection, captured.cookie)
        val metadata = runCatching { JSONObject(collection.payload.encoded) }.getOrElse { JSONObject() }
        val library = freshDirectory(captured)
        val keys = kugouCollectionFavoriteKeys(collection)
        val gid = keys.firstOrNull { it.startsWith("global:") }?.removePrefix("global:").orEmpty()
        val creator = metadata.optString("list_create_userid").takeIf { it.toLongOrNull()?.let { id -> id > 0 } == true }.orEmpty()
        val creatorList = metadata.optString("list_create_listid").takeIf { it.toLongOrNull()?.let { id -> id > 0 } == true }.orEmpty()
        val existing = library.firstOrNull {
            kugouCollectionFavoriteKeys(it.playlist.id, it.raw).any(keys::contains)
        }
        require(!collection.owned && !collection.accountFavorites && existing?.accountOwned != true) { "自己的歌单无需收藏" }
        state().check(captured)
        if (favorite) {
            if (existing == null) {
                val origin = if ((creator.isBlank() || creatorList.isBlank()) && gid.isNotBlank())
                    client.origin(captured.cookie, gid) else metadata
                state().check(captured)
                writing(captured) { beforeWrite -> client.collect(captured.cookie, collection.title, origin.optString("list_create_userid"),
                    origin.optString("list_create_listid"), gid, beforeWrite) }
            }
        } else if (existing != null) {
            require(existing.savedPlaylist) { "此歌单不是收藏副本" }
            writing(captured) { beforeWrite -> client.delete(captured.cookie, existing, beforeWrite) }
        } else {
            require(gid.isNotBlank() || (creator.isNotBlank() && creatorList.isNotBlank())) { "缺少收藏来源编号，请刷新后重试" }
        }
    }

    internal suspend fun writable(captured: KugouAccountRequest, collection: SourceCollection): KugouManagedCollection {
        requireAccount(collection, captured.cookie)
        return freshDirectory(captured).firstOrNull { it.playlist.id == collection.stableId && it.writable }
            ?: error("只能修改当前账号创建的普通歌单，请刷新后重试")
    }

    private suspend fun favorites(captured: KugouAccountRequest, fresh: Boolean = false): KugouManagedCollection {
        val guard = beforeWrite(captured)
        // 喜欢状态核对也发布同一份目录版本，避免新曲目快照被旧目录再次覆盖。
        val lists = if (fresh) directory.read(captured.cookie.requiredKugouSession().userId,
            captured.generation, SourceLibrarySection.FAVORITE_TRACKS, SourceFetchPolicy.REFRESH, guard,
            { client.library(captured.cookie, guard) }, { work -> state().scope.launch(Dispatchers.IO) { work() } })
        else accountDirectory(captured)
        return lists.singleOrNull { it.playlist.isPlatformFavorites }
            ?: error("酷狗未返回唯一的我喜欢歌单，请刷新账号后重试")
    }

    internal suspend fun freshDirectory(captured: KugouAccountRequest): List<KugouManagedCollection> =
        client.library(captured.cookie, beforeWrite(captured))

    private suspend fun accountDirectory(captured: KugouAccountRequest): List<KugouManagedCollection> {
        val guard = beforeWrite(captured)
        return directory.current(captured.cookie.requiredKugouSession().userId, captured.generation, guard) {
            client.library(captured.cookie, guard)
        }
    }

    private suspend fun withArtwork(target: KugouManagedCollection, captured: KugouAccountRequest): SourceCollection {
        val owner = captured.cookie.requiredKugouSession().userId
        if (target.playlist.isPlatformFavorites) {
            // 卡片与详情统一采用收藏顺序首曲封面，不退回目录爱心图或旧封面缓存。
            val snapshot = trackSnapshot(captured, target)
            state().check(captured)
            return snapshot.withFavoriteArtwork(mapped(target, captured))
        }
        // 收藏副本保留歌单本身封面，不能复用旧版按歌曲派生的封面缓存。
        if (!target.created) return mapped(target, captured)
        val stored = artwork.read(owner, target)
        if (stored == null) {
            val version: Any = target.trackSnapshotVersion() ?: target
            artworkPreparation.prepare(state().scope, "$owner:${captured.generation}:${target.listId}", version) {
                val guard = beforeWrite(captured)
                val url = trackSnapshot(captured, target).latestArtwork()
                guard()
                if (url != null) {
                    artwork.save(owner, target, url, guard)
                    guard()
                    changes.tryEmit(SourceLibraryChange(owner,
                        setOf(SourceLibrarySection.CREATED_COLLECTIONS, SourceLibrarySection.SAVED_COLLECTIONS)))
                }
            }
        }
        state().check(captured)
        return mapped(target, captured).let { collection ->
            if (stored == null) collection else collection.copy(artwork = collection.artwork.copy(url = stored))
        }
    }

    private suspend fun trackSnapshot(captured: KugouAccountRequest, target: KugouManagedCollection,
        policy: SourceFetchPolicy = SourceFetchPolicy.CACHE_FIRST): KugouCollectionTrackSnapshot {
        val guard = beforeWrite(captured)
        return collectionTracks.readStream(captured.cookie.requiredKugouSession().userId, captured.generation, target, guard, policy) { accept ->
            client.visitEntries(captured.cookie, target, guard, accept)
        }
    }

    internal suspend fun <T> writing(captured: KugouAccountRequest, operation: (beforeWrite: () -> Unit) -> T): T {
        val guard = beforeWrite(captured)
        return try { operation(guard) }
        finally { withContext(NonCancellable) {
            artworkPreparation.clear()
            directory.invalidate(captured.cookie.requiredKugouSession().userId, captured.generation)
            collectionTracks.invalidate(captured.cookie.requiredKugouSession().userId)
            artwork.invalidate(captured.cookie.requiredKugouSession().userId)
            changed(captured.cookie.requiredKugouSession().userId)
        } }
    }

    private suspend fun beforeWrite(captured: KugouAccountRequest): () -> Unit {
        val owner = state()
        val context = currentCoroutineContext()
        return { context.ensureActive(); owner.check(captured) }
    }

    private fun requireAccount(collection: SourceCollection, cookie: String) {
        require(collection.identity.sourceId == KUGOU_SOURCE_ID &&
            (collection.identity.accountId == null || collection.identity.accountId == cookie.requiredKugouSession().userId)) {
            "歌单属于其他音源或账号，请刷新后重试"
        }
    }

    internal fun mapped(value: KugouManagedCollection, captured: KugouAccountRequest): SourceCollection {
        catalog.remember(value.playlist, captured)
        return KugouSourceMapping.collection(value.playlist, captured.cookie.requiredKugouSession().userId).copy(
            payload = SourcePayload("kugou.collection", 1, JSONObject(value.raw.toString()).put("id", value.playlist.id).toString()))
    }

    private fun validateEdit(edit: SourceCollectionEdit) {
        require(edit.title.isNotBlank()) { "歌单名称不能为空" }
        require(edit.artworkUri.isNullOrBlank()) { "当前酷狗接入尚不支持上传歌单封面，请移除封面后重试" }
    }

}

/** 创建回执可不含编号；只接受本次写前不存在、写后唯一同名且可写的真实歌单。 */
internal fun kugouCreatedCollection(beforeIds: Set<String>, after: List<KugouManagedCollection>, title: String,
    responseId: String?): KugouManagedCollection {
    val candidates = after.filter { it.listId !in beforeIds && it.writable && it.playlist.title == title }
    return (if (responseId != null) candidates.singleOrNull { it.listId == responseId } else candidates.singleOrNull())
        ?: throw kugouCreateUnconfirmed()
}
