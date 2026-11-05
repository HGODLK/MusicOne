package com.musicone

import android.content.Context
import com.musicone.source.runtime.SourceInstance
import com.musicone.source.sdk.*
import com.musicone.sourceintegration.SourceApplicationHost
import com.musicone.sourceintegration.toMusicTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class SupplementalPage { HOME, ACCOUNT, LINK, FOLDERS, FOLDER, CONTENT, SETTINGS }
internal data class SupplementalState(
    val page: SupplementalPage = SupplementalPage.HOME, val publicPage: Boolean = false, val navigationRevision: Long = 0, val account: SourceAccount? = null,
    val collections: List<SourceRemoteCollection> = emptyList(), val loadingDirectory: Boolean = false,
    val directoryArtworkRevision: Long = 0, val artworkVersions: Map<TrackIdentity, Long> = emptyMap(),
    val directoryError: String? = null, val directoryCursor: String? = null, val directoryRetryMore: Boolean = false,
    val selectedCollection: SourceRemoteCollection? = null, val input: String = "",
    val content: SourceContent? = null, val children: List<SourceChildItem> = emptyList(),
    val selectedChildren: Set<TrackIdentity> = emptySet(), val resolving: Boolean = false,
    val busy: Boolean = false, val playingCollection: String? = null, val subscribing: Boolean = false,
    val targetId: String = UNIFIED_PLAYLIST_ID, val targetTitle: String = "综合歌单",
    val destinations: List<SourceLocalCollection> = emptyList(), val error: String? = null, val message: String? = null)

/** 只调用公开业务接口；页面退出取消请求，不关闭进程共享的音源实例。 */
internal class SupplementalSourceModel(context: Context, val instance: SourceInstance) : AutoCloseable {
    val entry get() = instance.entry
    private val host = SourceApplicationHost.get(context)
    private val library = host.library
    private val libraryStore = MusicLibraryStore.get(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(SupplementalState())
    val state = mutable.asStateFlow()
    private var accountRevision = Long.MIN_VALUE
    private val accountVersion = SupplementalAccountVersion()
    private var generation = 0L
    private var contentJob: Job? = null
    private var directoryJob: Job? = null
    private val stack = mutableListOf<SupplementalPage>()
    private val pageHistory = mutableListOf<SupplementalState>()
    val repository = entry.remoteCollections?.let { SupplementalContentRepository(it) { requestRevision() } }
    private val collectionArtwork = (entry.remoteCollections as? SourceRemoteCollectionArtwork)?.let(::SupplementalCollectionArtworkLoader)
    val sourceId get() = entry.descriptor.id
    val pageProvider get() = entry.supplementalPageProviderOrNull()
    val canPlay get() = supplementalCanPlay(entry)
    val canImport get() = supplementalCanImport(entry)
    fun canConvert(content: SourceContent, hasChildren: Boolean = false) = supplementalCanConvert(entry, content, hasChildren)
    val subscriptionAccountId get() = supplementalSubscriptionAccountId(entry, state.value.account)
    fun pageAvailable(page: SourceSupplementalPage): Boolean = supplementalPageAvailable(entry, page)
    fun navigate(page: SourceSupplementalPage, target: SourceSupplementalTarget, forcePublic: Boolean = false, asRoot: Boolean = false) {
        if (!pageAvailable(page)) return
        if (pageProvider != null) {
            require(page != SourceSupplementalPage.CONTENT || target is SourceSupplementalTarget.Content) { "内容页需要内容目标" }
            require(page != SourceSupplementalPage.COLLECTION || target is SourceSupplementalTarget.Collection) { "收藏夹详情页需要集合目标" }
            if ((forcePublic || publicOnlyMode) && !supplementalPublicPageAvailable(entry, page)) return
        }
        if (pageProvider != null && page == SourceSupplementalPage.CONTENT && target is SourceSupplementalTarget.Content) {
            openContent(target.content, asRoot, forcePublic); return
        }
        if (pageProvider != null && state.value.page == page.internalPage() && currentTarget(state.value) != target) pageHistory.add(state.value)
        open(page.internalPage())
        when (target) {
            is SourceSupplementalTarget.Collection -> mutable.update { it.copy(selectedCollection = target.collection) }
            is SourceSupplementalTarget.Content -> mutable.update { it.copy(content = target.content) }
            is SourceSupplementalTarget.Link -> mutable.update { it.copy(input = target.input) }
            else -> Unit
        }
        mutable.update { it.copy(publicPage = forcePublic) }
        if (asRoot) { stack.clear(); pageHistory.clear() }
        if (forcePublic && (page == SourceSupplementalPage.HOME || page == SourceSupplementalPage.COLLECTIONS)) loadCollections(refresh = false, forcePublic = true)
    }
    fun currentTarget(state: SupplementalState): SourceSupplementalTarget = when (state.page) {
        SupplementalPage.ACCOUNT -> SourceSupplementalTarget.Account
        SupplementalPage.LINK -> SourceSupplementalTarget.Link(state.input)
        SupplementalPage.FOLDERS -> SourceSupplementalTarget.Collections
        SupplementalPage.FOLDER -> state.selectedCollection?.let { SourceSupplementalTarget.Collection(it) } ?: SourceSupplementalTarget.Home
        SupplementalPage.CONTENT -> state.content?.let { SourceSupplementalTarget.Content(it) } ?: SourceSupplementalTarget.Home
        else -> SourceSupplementalTarget.Home
    }
    fun callbackCurrent(revision: Long): Boolean = revision != Long.MIN_VALUE && revision == requestRevision() && scope.isActive
    fun callbackRevision(): Long = requestRevision()
    val embeddedPageHeader = SupplementalEmbeddedPageHeader()
    private var publicOnlyMode = false
    fun enter(forcePublic: Boolean = false) {
        publicOnlyMode = forcePublic
        refreshAccount(); refreshDestinations()
        if (forcePublic) loadCollections(refresh = false, forcePublic = true)
    }
    fun startAt(target: SourceSupplementalTarget) {
        stack.clear(); pageHistory.clear()
        if (pageProvider != null) {
            val page = when (target) {
                SourceSupplementalTarget.Home -> SourceSupplementalPage.HOME
                SourceSupplementalTarget.Account -> SourceSupplementalPage.ACCOUNT
                SourceSupplementalTarget.Collections -> SourceSupplementalPage.COLLECTIONS
                is SourceSupplementalTarget.Collection -> SourceSupplementalPage.COLLECTION
                is SourceSupplementalTarget.Content -> SourceSupplementalPage.CONTENT
                is SourceSupplementalTarget.Link -> SourceSupplementalPage.LINK
            }
            navigate(page, target, forcePublic = publicOnlyMode, asRoot = true); return
        }
        when (target) {
            SourceSupplementalTarget.Home -> Unit
            SourceSupplementalTarget.Account -> mutable.update { it.copy(page = SupplementalPage.ACCOUNT) }
            SourceSupplementalTarget.Collections -> mutable.update { it.copy(page = SupplementalPage.FOLDERS) }
            is SourceSupplementalTarget.Link -> mutable.update { it.copy(page = SupplementalPage.LINK, input = target.input) }
            is SourceSupplementalTarget.Collection -> mutable.update {
                it.copy(page = SupplementalPage.FOLDER, selectedCollection = target.collection)
            }
            is SourceSupplementalTarget.Content -> openContent(target.content, asRoot = true)
        }
    }
    fun refreshAccount(): Boolean {
        val account = entry.authentication?.currentAccount()
        val modern = pageProvider != null
        val changed = if (modern) accountVersion.update(account) else (account?.revision ?: 0) != accountRevision
        if (changed) {
            accountRevision = account?.revision ?: 0; generation++
            // 新契约换号或修订后取消所有旧动作，不让导入、订阅等任务继续持有旧会话。
            if (modern) cancelSupplementalAccountRequests(scope)
            directoryJob?.cancel(); contentJob?.cancel(); stack.clear(); pageHistory.clear()
            embeddedPageHeader.clear()
            collectionArtwork?.clear()
            mutable.value = SupplementalState(account = account?.account,
                navigationRevision = if (modern) state.value.navigationRevision + 1 else 0)
            loadCollections(refresh = false)
        } else mutable.update { it.copy(account = account?.account) }
        return changed
    }
    fun refreshDestinations() = action {
        val revision = generation
        val values = library.destinations()
        if (pageProvider != null) ensureCurrent(revision)
        mutable.update { it.copy(destinations = values) }
    }
    fun open(page: SupplementalPage) {
        if (state.value.page != page) {
            if (pageProvider != null) pageHistory.add(state.value) else stack.add(state.value.page)
        }
        mutable.update { it.copy(page = page, navigationRevision = it.navigationRevision + 1, publicPage = false, error = null, message = null) }
    }
    fun back(): Boolean {
        if (embeddedPageHeader.back()) return true
        val current = state.value
        val customBack = pageProvider?.supplementalPagePolicy(current.page.publicPage())?.mode == SourceSupplementalPageMode.CUSTOM && !current.publicPage
        if ((current.page == SupplementalPage.ACCOUNT || (pageProvider != null && (customBack || current.page == SupplementalPage.SETTINGS))) && instance.onBack()) return true
        if (pageProvider != null) {
            val previous = pageHistory.removeLastOrNull() ?: return false
            contentJob?.cancel()
            mutable.value = previous.copy(navigationRevision = current.navigationRevision + 1,
                resolving = false, busy = false, error = null, message = null)
            refreshAccount(); refreshDestinations(); return true
        }
        val page = stack.removeLastOrNull() ?: return false
        contentJob?.cancel()
        mutable.update { it.copy(page = page, navigationRevision = it.navigationRevision + 1, publicPage = false, resolving = false, busy = false, error = null, message = null) }
        refreshAccount(); refreshDestinations(); return true
    }
    fun input(value: String) { mutable.update { it.copy(input = value) } }
    fun target(id: String, title: String) { mutable.update { it.copy(targetId = id, targetTitle = title) } }
    fun selectCollection(value: SourceRemoteCollection) {
        if (pageProvider != null) {
            if (!pageAvailable(SourceSupplementalPage.COLLECTION)) return
            if (state.value.page == SupplementalPage.FOLDER && state.value.selectedCollection != value) pageHistory.add(state.value)
            open(SupplementalPage.FOLDER); mutable.update { it.copy(selectedCollection = value) }
        } else { mutable.update { it.copy(selectedCollection = value) }; open(SupplementalPage.FOLDER) }
    }
    fun loadCollections(more: Boolean = false, refresh: Boolean = true, forcePublic: Boolean = false) {
        val service = entry.remoteCollections ?: return
        val mode = pageProvider?.supplementalPagePolicy(SourceSupplementalPage.COLLECTIONS)?.mode
        if (mode == SourceSupplementalPageMode.DISABLED ||
            (mode == SourceSupplementalPageMode.CUSTOM && !forcePublic && !publicOnlyMode && !state.value.publicPage)) return
        if (directoryJob?.isActive == true) return
        val revision = generation
        val cursor = if (more) state.value.directoryCursor ?: return else null
        directoryJob = scope.launch {
            mutable.update { it.copy(loadingDirectory = true, directoryError = null) }
            try {
                val page = service.collections(SourceCursorRequest(cursor, policy = if (refresh && !more) SourceFetchPolicy.REFRESH else SourceFetchPolicy.CACHE_FIRST))
                ensureCurrent(revision)
                require(page.nextCursor == null || page.nextCursor != cursor) { "音源返回了重复分页游标" }
                if (!more) collectionArtwork?.clear()
                mutable.update { old ->
                    val known = old.collections.associateBy { it.identity }
                    val incoming = page.items.map { folder ->
                        // 刷新目录时先保留已显示封面，实际新图由可见卡片独立准备。
                        folder.copy(artwork = known[folder.identity]?.artwork?.takeIf { collectionArtwork != null && !it.url.isNullOrBlank() }
                            ?: folder.artwork.takeIf { !it.url.isNullOrBlank() }
                            ?: known[folder.identity]?.artwork ?: folder.artwork)
                    }
                    old.copy(collections = (if (more) old.collections + incoming else incoming).distinctBy { it.identity },
                        directoryCursor = page.nextCursor,
                        directoryArtworkRevision = old.directoryArtworkRevision + if (more) 0 else 1)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (revision == generation) mutable.update { it.copy(directoryError = error.message ?: "读取收藏夹失败", directoryRetryMore = more) } }
            finally { if (revision == generation) mutable.update { it.copy(loadingDirectory = false) } }
        }
    }
    suspend fun loadCollectionArtwork(collection: SourceRemoteCollection, directoryRevision: Long) {
        val revision = generation
        val artwork = collectionArtwork?.load(collection, revision, directoryRevision) ?: return
        ensureCurrent(revision)
        mutable.update { it.withCollectionArtwork(collection.identity, artwork, directoryRevision) }
    }
    fun resolveInput() = resolve { checkNotNull(entry.contents) { "此音源未提供内容解析" }.resolve(state.value.input) }
    fun openContent(content: SourceContent) = openContent(content, false)
    private fun openContent(content: SourceContent, asRoot: Boolean, forcePublic: Boolean = false) = resolve(asRoot, forcePublic) { entry.contents?.detail(content) ?: content }
    private fun resolve(asRoot: Boolean = false, forcePublic: Boolean = false, block: suspend () -> SourceContent) {
        if (pageProvider != null && !pageAvailable(SourceSupplementalPage.CONTENT)) return
        contentJob?.cancel(); val revision = generation
        val navigationRevision = state.value.navigationRevision
        contentJob = scope.launch {
            mutable.update { it.copy(resolving = true, error = null) }
            try {
                val content = block(); ensureCurrent(revision)
                if (pageProvider != null && state.value.navigationRevision != navigationRevision) throw CancellationException("页面已改变")
                val children = entry.childItems?.let { service -> collectPages { cursor -> service.children(content, SourceCursorRequest(cursor)) } }.orEmpty()
                ensureCurrent(revision)
                if (pageProvider != null && state.value.navigationRevision != navigationRevision) throw CancellationException("页面已改变")
                if (pageProvider != null) {
                    if (state.value.page == SupplementalPage.CONTENT && state.value.content != content) pageHistory.add(state.value)
                    open(SupplementalPage.CONTENT)
                    mutable.update { it.copy(publicPage = forcePublic) }
                }
                mutable.update { it.copy(content = content, children = children, selectedChildren = children.filter { child -> child.initiallySelected }.mapTo(linkedSetOf()) { child -> child.identity }) }
                if (pageProvider == null) open(SupplementalPage.CONTENT)
                if (asRoot) { stack.clear(); pageHistory.clear() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (revision == generation) mutable.update { it.copy(error = error.message ?: "解析内容失败") } }
            finally { if (revision == generation) mutable.update { it.copy(resolving = false) } }
        }
    }
    fun toggleChild(identity: TrackIdentity) { mutable.update { it.copy(selectedChildren = if (identity in it.selectedChildren) it.selectedChildren - identity else it.selectedChildren + identity) } }
    fun selectAllChildren() { mutable.update { it.copy(selectedChildren = it.children.mapTo(linkedSetOf()) { child -> child.identity }) } }
    fun clearChildren() { mutable.update { it.copy(selectedChildren = emptySet()) } }
    fun importSelected(play: Boolean = false) = action(busy = true) {
        val current = state.value; val content = checkNotNull(current.content)
        requireSupplementalAction(entry, play, listOf(content))
        val selected = current.children.filter { it.identity in current.selectedChildren }
        require(current.children.isEmpty() || selected.isNotEmpty()) { "请至少选择一个子项目" }
        val revision = generation
        val tracks = supplementalTracks(entry, content, selected)
        ensureCurrent(revision); require(tracks.isNotEmpty()) { "没有可用曲目" }
        if (play) host.playDetailed(tracks, 0) else library.importTracks(tracks, current.targetId)
        if (pageProvider != null) ensureCurrent(revision)
        mutable.update { it.copy(message = if (play) "已开始播放" else "已加入${current.targetTitle}") }
    }
    fun playContent(content: SourceContent) {
        if (pageProvider != null) { importContents(listOf(content), play = true); return }
        val track = content.playableTrack
        if (track != null) host.playDetailed(listOf(track), 0) else openContent(content)
    }
    fun importContents(contents: List<SourceContent>, play: Boolean = false) = action(busy = true) {
        requireSupplementalAction(entry, play, contents)
        val revision = generation; val target = state.value.targetId
        val tracks = contents.flatMap { content ->
            if (pageProvider != null) {
                supplementalDefaultTracks(entry, content)
            } else content.playableTrack?.let { listOf(it) } ?: run {
                val children = entry.childItems?.let { service -> collectPages { cursor -> service.children(content, SourceCursorRequest(cursor)) } }.orEmpty()
                val selected = children.filter { it.initiallySelected }
                require(children.isEmpty() || selected.isNotEmpty()) { "请先打开“${content.title}”选择子项目" }
                checkNotNull(entry.importing) { "此音源未提供导入" }.tracks(content, selected)
            }
        }.distinctBy { it.stableId }
        ensureCurrent(revision); require(tracks.isNotEmpty()) { "没有可用曲目" }
        if (play) host.playDetailed(tracks, 0) else library.importTracks(tracks, target)
        if (pageProvider != null) ensureCurrent(revision)
        mutable.update { it.copy(message = if (play) "已开始播放" else "已加入${it.targetTitle}") }
    }
    fun playCollection(collection: SourceRemoteCollection, sort: PlaylistSort = PlaylistSort.ADDED_DESC) = action {
        requireSupplementalAction(entry, play = true)
        if (state.value.playingCollection != null) return@action
        val revision = generation
        mutable.update { it.copy(playingCollection = collection.identity.remoteId) }
        try {
            val repo = checkNotNull(repository)
            val contents = sortedSupplementalContents(repo.complete(repo.entry(collection, "")).contents, sort)
            val tracks = if (pageProvider != null) contents.flatMap { content ->
                supplementalDefaultTracks(entry, content)
            }.distinctBy { it.stableId } else contents.mapNotNull { it.playableTrack }
            ensureCurrent(revision); require(tracks.isNotEmpty()) { "收藏夹中暂无可直接播放的内容，请选择子项目" }
            host.playDetailed(tracks, 0)
        } finally { if (pageProvider == null || revision == generation) mutable.update { it.copy(playingCollection = null) } }
    }
    fun subscribe(collection: SourceRemoteCollection, existing: LibrarySubscription? = null) = action {
        if (state.value.subscribing) return@action
        val accountId = subscriptionAccountId ?: error("请先登录后订阅")
        val service = checkNotNull(entry.subscriptions) { "此音源未提供订阅" }
        val revision = generation
        val effectiveExisting = existing ?: if (pageProvider != null) libraryStore.state.value.subscriptions.firstOrNull {
            it.sourceId == sourceId && it.accountId == accountId && it.remoteCollectionId == collection.identity.remoteId
        } else null
        val oldIdentity = effectiveExisting?.let { SourceSubscriptionIdentity(sourceId, accountId, collection.identity.remoteId, it.playlistId) }
        mutable.update { it.copy(subscribing = true) }
        try {
            val expected = oldIdentity?.let { library.subscriptionRevision(it) }
            if (pageProvider != null) ensureCurrent(revision)
            val items = collectPages { cursor -> service.page(collection, SourceCursorRequest(cursor)) }
            ensureCurrent(revision)
            check(supplementalSubscriptionAccountId(entry, entry.authentication?.currentAccount()?.account) == accountId) { "订阅身份已变化，请重试" }
            // 新订阅只有完整读取成功后才创建独立歌单，失败分页不留下半成品。
            val identity = oldIdentity ?: SourceSubscriptionIdentity(sourceId, accountId, collection.identity.remoteId,
                library.createCollection(collection.title).id)
            ensureCurrent(revision)
            check(supplementalSubscriptionAccountId(entry, entry.authentication?.currentAccount()?.account) == accountId) { "订阅身份已变化，请重试" }
            val expectedRevision = expected ?: library.subscriptionRevision(identity)
            if (pageProvider != null) ensureCurrent(revision)
            library.commitSubscription(identity, expectedRevision, items)
            if (pageProvider != null) ensureCurrent(revision)
            mutable.update { it.copy(message = "订阅已同步") }; refreshDestinations()
        } finally { if (pageProvider == null || revision == generation) mutable.update { it.copy(subscribing = false) } }
    }
    fun logout() = action(busy = true) {
        checkNotNull(entry.authentication) { "此音源未提供账号能力" }.logout(); refreshAccount()
    }
    private fun subscriptionFor(collection: SourceRemoteCollection): LibrarySubscription? = libraryStore.state.value.subscriptions.firstOrNull {
        it.sourceId == sourceId && it.accountId == subscriptionAccountId && it.remoteCollectionId == collection.identity.remoteId
    }
    fun refreshSubscription(collection: SourceRemoteCollection) {
        val existing = subscriptionFor(collection)
        if (existing == null) { action { error("尚未订阅该收藏夹") }; return }
        subscribe(collection, existing)
    }
    fun removeCollectionSubscription(collection: SourceRemoteCollection) {
        subscriptionFor(collection)?.let { removeSubscription(it) }
    }
    fun removeSubscription(subscription: LibrarySubscription) = action {
        val revision = generation
        if (pageProvider != null) check(subscription.sourceId == sourceId && subscription.accountId == subscriptionAccountId) { "订阅身份已变化，请重试" }
        library.removeSubscription(SourceSubscriptionIdentity(subscription.sourceId, subscription.accountId,
            subscription.remoteCollectionId, subscription.playlistId), true)
        if (pageProvider != null) ensureCurrent(revision)
        mutable.update { it.copy(message = "已取消订阅，歌曲已保留") }; refreshDestinations()
    }
    private fun requestRevision(): Long {
        val account = entry.authentication?.currentAccount()
        val current = if (pageProvider != null) accountVersion.matches(account) else (account?.revision ?: 0) == accountRevision
        return if (current) generation else Long.MIN_VALUE
    }
    private fun ensureCurrent(revision: Long) { if (revision != requestRevision() || !scope.isActive) throw CancellationException("页面或账号已改变") }
    private fun action(busy: Boolean = false, block: suspend () -> Unit): Job = scope.launch {
        val revision = generation
        if (busy && pageProvider != null && state.value.busy) return@launch
        if (busy) mutable.update { it.copy(busy = true) }
        mutable.update { it.copy(error = null, message = null) }
        try { if (pageProvider != null) ensureCurrent(revision); block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (revision == generation) mutable.update { it.copy(error = error.message ?: "操作失败") } }
        finally { if (busy && revision == generation) mutable.update { it.copy(busy = false) } }
    }
    override fun close() { generation++; embeddedPageHeader.clear(); scope.cancel() }
}

internal suspend fun <T> collectPages(fetch: suspend (String?) -> SourcePage<T>): List<T> {
    val result = mutableListOf<T>(); val seen = hashSetOf<String>(); var cursor: String? = null
    do {
        currentCoroutineContext().ensureActive()
        val page = fetch(cursor); result.addAll(page.items); cursor = page.nextCursor
        cursor?.let { require(seen.add(it)) { "音源分页游标形成循环" } }
    } while (cursor != null)
    return result
}

internal fun SourceRemoteCollection.asSupplementalPlaylist(tracks: List<MusicTrack> = emptyList()) = MusicPlaylist(
    "source-collection:${identity.sourceId}:${identity.accountId}:${identity.remoteId}", MusicSource.fromId(identity.sourceId), title,
    count?.let { "$it 个内容" }.orEmpty(), "", count ?: tracks.size, artwork.startArgb, artwork.endArgb, artwork.mark,
    tracks, artwork.url, providerData = mapOf("sourceRemoteCollectionId" to identity.remoteId,
        "sourceAccountId" to identity.accountId.orEmpty(), "sourcePayloadFormat" to payload.format,
        "sourcePayloadVersion" to payload.version.toString(), "sourcePayload" to payload.encoded),
    sourceAccountId = identity.accountId, remoteKey = identity.remoteId)

internal fun SourceContent.asSupplementalTrack(): MusicTrack = (playableTrack ?: SourceTrackDetails(
    SourceTrack(identity, title, listOfNotNull(author?.name), artwork.url),
    "${identity.sourceId}:${identity.remoteId}", durationMs = durationMs, artwork = artwork,
    artistsText = author?.name.orEmpty(), access = access)).toMusicTrack()
