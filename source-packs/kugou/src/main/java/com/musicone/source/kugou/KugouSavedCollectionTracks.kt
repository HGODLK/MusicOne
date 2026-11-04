package com.musicone.source.kugou

import com.musicone.source.sdk.*
import org.json.JSONObject

/** 只有已收藏的外部歌单可以按目录记录回到原始歌单，不能把副本的 global 编号当成原始编号。 */
internal fun KugouManagedCollection.savedOriginId(): String? =
    if (!savedPlaylist) null else raw.optString("list_create_gid")
        .takeIf { it.isNotBlank() && it != "0" && it != "null" && it != raw.optString("global_collection_id") }

/** 仅表示首屏明确返回空副本；鉴权、网络和数据格式错误继续按原错误交付。 */
internal class KugouEmptySavedCollection : PlatformApiException("酷狗收藏副本尚未返回歌曲")

internal fun KugouManagedCollection.hasEmptySavedCopy(data: JSONObject, page: Int): Boolean =
    page == 1 && playlist.count > 0 && savedOriginId() != null &&
        data.opt("count")?.toString()?.toLongOrNull() == 0L &&
        ((data.has("info") && data.isNull("info")) || data.optJSONArray("info")?.length() == 0)

/** 只切换歌曲读取来源，收藏副本的身份、元数据和收藏写入口保持原样。 */
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
        // 首屏选定原始歌单后，后续页固定沿用同一来源，避免副本恢复时混入另一套分页。
        return page.copy(nextCursor = page.nextCursor?.let { requireNotNull(prefix) + it })
    }
}
