package com.musicone.source.bilibili

import com.musicone.source.sdk.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

internal class BiliSourceContents(private val state: () -> BiliPackageState) : SourceContents, SourceChildItems, SourceRemoteCollections, SourceRemoteCollectionArtwork, SourceImporting, SourceSubscriptions {
    override suspend fun resolve(input: String): SourceContent = state().request { request ->
        val expanded = BiliSourceMapping.expandLink(input)
        val bvid = BiliSourceMapping.bvid(expanded) ?: BiliSourceMapping.bvid(input) ?: error("未能识别视频链接或 BV 号")
        val preferred = BiliSourceMapping.selectedPage(expanded) ?: BiliSourceMapping.selectedPage(input)
        val raw = state().api.getVideoDetails(bvid, request.session?.cookie.orEmpty())
        val details = raw.copy(pages = raw.pages.ifEmpty { state().api.getVideoPages(bvid, request.session?.cookie.orEmpty()) })
        require(details.pages.isNotEmpty()) { "未找到可导入的分 P" }
        if (preferred != null) require(details.pages.any { it.page == preferred }) { "链接指定的分 P 已不存在" }
        BiliSourceMapping.content(details.video(), request.session?.mid, preferred)
    }
    override suspend fun detail(content: SourceContent): SourceContent = state().request { request ->
        validateAccount(content.identity, request)
        val previous = BiliSourceMapping.payload(content.payload)
        val video = BiliSourceMapping.video(content)
        val details = state().api.getVideoDetails(video.bvid, request.session?.cookie.orEmpty())
        BiliSourceMapping.content(details.video(), request.session?.mid, previous.optInt("selectedPage").takeIf { it > 0 })
    }
    override suspend fun children(content: SourceContent, request: SourceCursorRequest): SourcePage<SourceChildItem> = state().request { account ->
        validateAccount(content.identity, account)
        require(request.cursor == null) { "分 P 列表不支持额外游标" }
        val video = BiliSourceMapping.video(content)
        val pages = state().api.getVideoPages(video.bvid, account.session?.cookie.orEmpty())
        val preferred = BiliSourceMapping.payload(content.payload).optInt("selectedPage").takeIf { it > 0 }
        SourcePage(pages.map { part -> SourceChildItem(TrackIdentity(BILI_SOURCE_ID, account.session?.mid, part.cid.toString()), part.page,
            part.part, part.durationMs, SourcePayload("bilibili.child", 1, JSONObject().put("bvid", video.bvid).put("cid", part.cid)
                .put("selected", preferred == null || part.page == preferred).toString()),
            initiallySelected = preferred == null || part.page == preferred) }, null)
    }
    override suspend fun collections(request: SourceCursorRequest): SourcePage<SourceRemoteCollection> = state().request(loginRequired = true) { captured ->
        require(request.cursor == null) { "收藏夹目录不支持额外游标" }
        val session = requireNotNull(captured.session)
        val folders = if (request.policy == SourceFetchPolicy.CACHE_FIRST) state().cache.folders(session.mid).takeIf { it.isNotEmpty() } else null
        // 目录只读取元数据；独立封面请求由宿主按可见卡片触发，避免整份目录逐夹等待。
        val previous = state().cache.folders(session.mid).associateBy { it.id }
        val result = folders ?: state().api.getFavoriteFolders(session.mid.toLong(), session.cookie).map { folder ->
            folder.copy(cover = previous[folder.id]?.cover?.takeIf(String::isNotBlank) ?: folder.cover)
        }
        currentCoroutineContext().ensureActive(); state().check(captured)
        if (folders == null) state().cache.saveFolders(session.mid, result)
        SourcePage(result.map { SourceRemoteCollection(TrackIdentity(BILI_SOURCE_ID, session.mid, it.id.toString()),
            it.title, it.mediaCount, SourceArtwork(it.cover.takeIf(String::isNotBlank))) }, null)
    }
    override suspend fun artwork(collection: SourceRemoteCollection, policy: SourceFetchPolicy): SourceArtwork = state().request(loginRequired = true) { captured ->
        validateAccount(collection.identity, captured)
        val session = requireNotNull(captured.session)
        val cover = state().api.getFavoriteFolderCover(collection.identity.remoteId.toLong(), session.cookie)
        currentCoroutineContext().ensureActive(); state().check(captured)
        if (cover.isNotBlank()) runCatchingCancellable { synchronized(state().cache) {
            // 可见卡片解析出的真实封面写回目录缓存，下一次进入不退回旧目录封面。
            val cached = state().cache.folders(session.mid)
            state().cache.saveFolders(session.mid, cached.map {
                if (it.id.toString() == collection.identity.remoteId) it.copy(cover = cover) else it
            })
        } }
        collection.artwork.copy(url = cover.takeIf(String::isNotBlank) ?: collection.artwork.url)
    }
    override suspend fun contents(collection: SourceRemoteCollection, query: String?, request: SourceCursorRequest): SourcePage<SourceContent> = state().request(loginRequired = true) { captured ->
        validateAccount(collection.identity, captured)
        val session = requireNotNull(captured.session)
        val folderId = collection.identity.remoteId.toLong()
        val entry = state().folders.entry(session.mid, folderId, query.orEmpty())
        val before = if (request.cursor == null) null else state().folders.first(session, entry)
        if (request.cursor != null) require(request.cursor == cursor(session.mid, folderId, before!!.page + 1)) { "收藏夹分页游标已失效，请刷新" }
        val result = if (request.cursor == null) state().folders.first(session, entry, request.policy == SourceFetchPolicy.REFRESH)
            else state().folders.more(session, entry)
        val pageItems = if (before == null) result.videos else result.videos.drop(before.videos.size)
        SourcePage(pageItems.map { BiliSourceMapping.content(it, session.mid) },
            if (result.hasMore) cursor(session.mid, folderId, result.page + 1) else null)
    }
    override suspend fun tracks(content: SourceContent, selectedChildren: List<SourceChildItem>): List<SourceTrackDetails> = state().request { captured ->
        validateAccount(content.identity, captured)
        require(selectedChildren.isNotEmpty()) { "请选择需要导入的子条目" }
        val video = BiliSourceMapping.video(content)
        val all = state().api.getVideoPages(video.bvid, captured.session?.cookie.orEmpty())
        val selected = selectedChildren.map { child ->
            validateAccount(child.identity, captured)
            val payload = BiliSourceMapping.payload(child.payload)
            require(payload.optString("bvid") == video.bvid) { "所选分 P 不属于当前视频" }
            all.firstOrNull { it.cid.toString() == child.identity.remoteId } ?: error("所选分 P 已不存在，请刷新")
        }.distinctBy { it.cid }
        selected.map { BiliSourceMapping.track(video, it, captured.session?.mid, all.size > 1) }
    }
    override suspend fun page(collection: SourceRemoteCollection, request: SourceCursorRequest): SourcePage<SourceSubscriptionItem> = state().request(loginRequired = true) { captured ->
        validateAccount(collection.identity, captured)
        val session = requireNotNull(captured.session); val folder = collection.identity.remoteId.toLong()
        val prefix = "${session.mid}:$folder:"
        val page = request.cursor?.let { require(it.startsWith(prefix)) { "订阅分页账号或集合不匹配" }; it.removePrefix(prefix).toInt() } ?: 1
        require(page > 0)
        val response = state().api.getFavoriteVideos(folder, page, session.cookie)
        require(!response.hasMore || response.videos.isNotEmpty()) { "收藏夹分页不完整，已保留原订阅" }
        val items = response.videos.mapIndexed { index, video ->
            SourceSubscriptionItem(video.bvid.ifBlank { "av${video.aid}" }, listOf(BiliSourceMapping.track(video, null, session.mid, false)),
                (page - 1) * 20L + index)
        }
        SourcePage(items, if (response.hasMore) cursor(session.mid, folder, page + 1) else null)
    }
    private fun cursor(account: String, folder: Long, page: Int) = "$account:$folder:$page"
    private fun validateAccount(identity: TrackIdentity, request: BiliAccountRequest) {
        require(identity.sourceId == BILI_SOURCE_ID)
        require(identity.accountId == null || identity.accountId == request.session?.mid) { "内容所属账号已变化，请重新打开" }
    }
    private fun BiliVideoDetails.video() = BiliVideoItem(aid, bvid, pages.firstOrNull()?.cid ?: 0,
        title, cover, upName, durationSec)
}
