package com.musicone.source.kugou

import com.musicone.source.sdk.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import org.json.JSONObject

/** 首页真实内容的只读展示集合；不创建云歌单，也不改动快照中的歌曲身份。 */
internal class KugouHomeCollections(
    private val currentOwner: () -> String?,
    private val delegate: SourceCollections,
) : SourceCollections by delegate, SourceCollectionCachePolicy, SourceCollectionReturnBehavior, SourceLibraryChanges {
    override val libraryChanges get() = (delegate as? SourceLibraryChanges)?.libraryChanges ?: kotlinx.coroutines.flow.emptyFlow()
    private data class View(val owner: String?, val remote: String, val kind: String, val tracks: List<SourceTrackDetails>)
    private val views = java.util.concurrent.ConcurrentHashMap<String, View>()

    private val retained = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Synchronized fun clearMemory() { retained.clear(); views.clear() }
    fun retain(collection: SourceCollection) {
        if (collection.isKugouHomeCollection()) retained.add(JSONObject(collection.payload.encoded).getString("kugouHomeToken"))
    }
    fun release(collection: SourceCollection) {
        if (collection.isKugouHomeCollection()) retained.remove(JSONObject(collection.payload.encoded).getString("kugouHomeToken"))
    }

    override val refreshLibraryAfterAnyReturn: Boolean
        get() = (delegate as? SourceCollectionReturnBehavior)?.refreshLibraryAfterAnyReturn ?: false

    override fun includesInCacheLibrary(collection: SourceCollection): Boolean =
        !collection.isKugouHomeCollection() &&
            ((delegate as? SourceCollectionCachePolicy)?.includesInCacheLibrary(collection) ?: true)

    @Synchronized fun collection(kind: String, day: String?, tracks: List<SourceTrackDetails>): SourceCollection {
        val owner = currentOwner()
        val title = homeTitle(kind)
        val date = day.orEmpty()
        require(kind != "daily" || date.isNotBlank()) { "每日推荐缺少真实日期，请刷新首页" }
        tracks.forEach { requireTrack(it, owner) }
        val remote = homeRemote(kind, owner, date)
        // 页面只传轻量引用；完整且不可变的快照留在包内，不在主线程反复编码。
        val token = UUID.randomUUID().toString()
        views.entries.removeIf { it.value.owner != owner || it.value.remote == remote && it.key !in retained }
        views[token] = View(owner, remote, kind, tracks)
        val data = JSONObject().put("kugouHomeKind", kind).put("kugouHomeOwner", owner.orEmpty())
            .put("kugouHomeDay", date).put("kugouHomeToken", token)
        require(currentOwner() == owner) { "酷狗账号已变化，请刷新首页" }
        return SourceCollection(TrackIdentity(KUGOU_SOURCE_ID, owner, remote), "kugou-$remote", title,
            subtitle = "${tracks.size} 首歌曲", count = tracks.size,
            artwork = tracks.firstOrNull()?.artwork ?: SourceArtwork(mark = title.take(1)),
            payload = SourcePayload("musicone.provider-data", 1, data.toString()))
    }

    override suspend fun detail(collection: SourceCollection): SourceCollection {
        if (!collection.isKugouHomeCollection()) return delegate.detail(collection)
        val snapshot = snapshot(collection)
        // 初始入口不能标记临时集合；公共详情先读取此处及完整 tracks 后再按已加载列表呈现。
        val data = JSONObject(collection.payload.encoded).apply { if (snapshot.kind == "daily") put("templateEphemeral", "true") }
        return collection.copy(title = homeTitle(snapshot.kind), subtitle = "${snapshot.tracks.size} 首歌曲",
            count = snapshot.tracks.size, artwork = snapshot.tracks.firstOrNull()?.artwork ?: collection.artwork,
            owned = false, accountFavorites = false, payload = collection.payload.copy(encoded = data.toString()))
    }

    override suspend fun tracks(collection: SourceCollection, request: SourceCursorRequest): SourcePage<SourceTrackDetails> {
        if (!collection.isKugouHomeCollection()) return delegate.tracks(collection, request)
        return withContext(Dispatchers.Default) {
            val value = snapshot(collection)
            if (value.kind == "daily") {
                require(request.cursor == null) { "每日推荐已经完整返回" }
                SourcePage(value.tracks, null)
            } else {
                require(request.limit > 0) { "分页数量必须大于零" }
                val token = JSONObject(collection.payload.encoded).getString("kugouHomeToken")
                val offset = request.cursor?.let { cursor ->
                    val parts = cursor.split(':')
                    require(parts.size == 3 && parts[0] == "kghv1" && parts[1] == token) { "最近播放游标已失效" }
                    parts[2].toIntOrNull()?.takeIf { it in 0..value.tracks.size }
                        ?: throw IllegalArgumentException("最近播放游标无效")
                } ?: 0
                val end = minOf(value.tracks.size.toLong(), offset.toLong() + request.limit).toInt()
                SourcePage(value.tracks.subList(offset, end), if (end < value.tracks.size) "kghv1:$token:$end" else null)
            }
        }
    }

    private suspend fun snapshot(collection: SourceCollection): HomeSnapshot {
        currentCoroutineContext().ensureActive()
        val owner = currentOwner()
        require(collection.identity.sourceId == KUGOU_SOURCE_ID && collection.payload.format == "musicone.provider-data") {
            "首页列表音源不匹配，请重新打开"
        }
        val data = JSONObject(collection.payload.encoded)
        require(!data.has("kugouHomeTracks")) { "请返回首页重新打开旧列表" }
        val kind = data.getString("kugouHomeKind")
        homeTitle(kind)
        val remote = homeRemote(kind, owner, data.getString("kugouHomeDay"))
        val view = views[data.getString("kugouHomeToken")] ?: error("首页快照已失效，请重新打开")
        require(view.owner == owner && data.getString("kugouHomeOwner") == owner.orEmpty() &&
            collection.identity.accountId == owner && view.remote == remote && view.kind == kind &&
            collection.identity.remoteId == remote && collection.stableId == "kugou-$remote") { "首页快照归属不匹配" }
        return HomeSnapshot(kind, view.tracks)
    }

    private data class HomeSnapshot(val kind: String, val tracks: List<SourceTrackDetails>)
}

internal fun SourceCollection.isKugouHomeCollection(): Boolean = stableId.startsWith("kugou-home-view:") ||
    identity.remoteId.startsWith("home-view:") || (payload.format == "musicone.provider-data" &&
        runCatching { JSONObject(payload.encoded).has("kugouHomeKind") }.getOrDefault(false))

private fun homeTitle(kind: String) = when (kind) {
    "daily" -> "每日推荐"
    "recent" -> "最近播放"
    else -> throw IllegalArgumentException("不支持的首页列表")
}

private fun homeRemote(kind: String, owner: String?, day: String) = "home-view:$kind:${owner ?: "guest"}:$day"

private fun requireTrack(track: SourceTrackDetails, owner: String?) {
    require(track.track.identity.sourceId == KUGOU_SOURCE_ID && track.track.identity.accountId == owner) {
        "首页列表包含其他账号或音源歌曲，请刷新首页"
    }
}
