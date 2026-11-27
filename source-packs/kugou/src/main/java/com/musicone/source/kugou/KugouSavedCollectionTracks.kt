package com.musicone.source.kugou

import com.musicone.source.sdk.*
import org.json.JSONObject

/** 提取已收藏外部歌单对应的原始歌单 ID（不能把收藏副本自身的 global ID 当原始 ID）。 */
internal fun KugouManagedCollection.savedOriginId(): String? =
    if (!savedPlaylist) null else raw.optString("list_create_gid")
        .takeIf { it.isNotBlank() && it != "0" && it != "null" && it != raw.optString("global_collection_id") }

/** 收藏副本首屏明确返回空列表时抛此异常，用来触发回退查原始歌单（网络/鉴权错不走回退）。 */
internal class KugouEmptySavedCollection : PlatformApiException("酷狗收藏副本尚未返回歌曲")

internal fun KugouManagedCollection.hasEmptySavedCopy(data: JSONObject, page: Int): Boolean =
    page == 1 && playlist.count > 0 && savedOriginId() != null &&
        data.opt("count")?.toString()?.toLongOrNull() == 0L &&
        ((data.has("info") && data.isNull("info")) || data.optJSONArray("info")?.length() == 0)

/** 收藏的外部歌单副本返回空时，改从原始歌单拉歌曲列表（歌单 ID 和收藏操作仍走副本）。 */
internal class KugouSavedCollectionTracks(private val publicCollections: KugouPublicCollectionClient) {
    suspend fun read(collection: SourceCollection, target: KugouManagedCollection, request: SourceCursorRequest,
        cookie: String, vip: Boolean, check: () -> Unit,
        loadCopy: suspend () -> SourcePage<SourceTrackDetails>): SourcePage<SourceTrackDetails> {
        val origin = target.savedOriginId()
        val prefix = origin?.let {
            "kg-origin:" + KugouProtocol.md5("${cookie.requiredKugouSession().userId}:${target.listId}:$it") + ":"
        }
        val cursor = request.cursor
        val originRequest = if (cursor?.startsWith("kg-origin:") == true) {
            require(prefix != null && cursor.startsWith(prefix)) { "酷狗收藏歌单游标已失效，请刷新" }
            request.copy(cursor = cursor.removePrefix(prefix))
        } else {
            try { return loadCopy() }
            catch (empty: KugouEmptySavedCollection) {
                if (cursor != null || origin == null) throw empty
                request
            }
        }
        check()
        val page = publicCollections.tracks(collection, originRequest, cookie, vip, requireNotNull(origin))
        check()
        // 第一页确定走原始歌单后，后续翻页都在游标里锁死同一来源，避免前后页混在一起
        return page.copy(nextCursor = page.nextCursor?.let { requireNotNull(prefix) + it })
    }
}
