package com.musicone.source.kugou

import com.musicone.source.sdk.*
import org.json.JSONArray
import org.json.JSONObject

internal fun interface KugouPublicCollectionTransport {
    fun request(path: String, params: Map<String, String>, body: JSONObject?, cookie: String, router: String?): JSONObject
}

/** 公开全局歌单与旧数字 specialid 属于不同协议，只在本包内分流。 */
internal class KugouPublicCollectionClient(
    private val transport: KugouPublicCollectionTransport = KugouPublicCollectionTransport(::requestKugouPublicCollection),
) {
    fun detail(collection: SourceCollection, cookie: String): SourceCollection {
        val gid = requireNotNull(collection.kugouGlobalCollectionId()) { "缺少酷狗全局歌单编号" }
        val credentials = cookie.cookieValues()
        val response = transport.request("/v3/get_list_info", emptyMap(), JSONObject()
            .put("data", JSONArray().put(JSONObject().put("global_collection_id", gid)))
            .put("userid", credentials["userid"].orEmpty().ifBlank { "0" }).put("token", credentials["token"].orEmpty()),
            cookie, "pubsongs.kugou.com")
        response.requireKugouCollectionSuccess()
        return collection.withKugouPublicInfo(response.kugouPublicInfo(gid))
    }

    fun tracks(collection: SourceCollection, request: SourceCursorRequest, cookie: String, vip: Boolean,
        gid: String = requireNotNull(collection.kugouGlobalCollectionId()) { "缺少酷狗全局歌单编号" }): SourcePage<SourceTrackDetails> {
        val offset = request.cursor?.toIntOrNull() ?: if (request.cursor == null) 0 else error("酷狗歌单游标无效")
        require(offset >= 0 && request.limit > 0) { "酷狗歌单分页参数无效" }
        val response = transport.request("/pubsongs/v2/get_other_list_file_nofilt", mapOf(
            "global_collection_id" to gid, "begin_idx" to offset.toString(), "pagesize" to request.limit.toString(),
            "area_code" to "1", "plat" to "1", "type" to "1", "mode" to "1", "personal_switch" to "1",
            "extend_fields" to "abtags,hot_cmt,popularization"), null, cookie, null)
        response.requireKugouCollectionSuccess()
        val data = response.optJSONObject("data") ?: throw PlatformApiException("酷狗未返回公开歌单歌曲")
        val rows = data.optJSONArray("songs") ?: throw PlatformApiException("酷狗未返回公开歌单歌曲列表")
        val next = Math.addExact(offset, rows.length())
        val count = data.opt("count")?.toString()?.toLongOrNull()
        if (rows.length() == 0 && count != null && offset < count) throw PlatformApiException("酷狗公开歌单分页提前中断，请重试")
        val tracks = (0 until rows.length()).map { index ->
            val raw = rows.optJSONObject(index) ?: throw PlatformApiException("酷狗公开歌单歌曲格式错误")
            raw.kugouPublicCollectionItem(gid, collection.identity.accountId, vip)
        }
        return SourcePage(tracks, when {
            rows.length() == 0 -> null
            count != null -> if (next.toLong() < count) next.toString() else null
            else -> next.toString()
        })
    }
}

private fun requestKugouPublicCollection(path: String, values: Map<String, String>, body: JSONObject?,
    cookie: String, router: String?): JSONObject {
    val credentials = cookie.cookieValues()
    val params = linkedMapOf("dfid" to credentials["dfid"].orEmpty().ifBlank { "-" },
        "mid" to credentials["KUGOU_API_MID"].orEmpty().ifBlank { "-" }, "uuid" to "-",
        "appid" to KugouProtocol.appId, "clientver" to KugouProtocol.clientVersion,
        "clienttime" to (System.currentTimeMillis() / 1000).toString()).apply { putAll(values) }
    val url = KugouProtocol.signedAndroidUrl("https://gateway.kugou.com$path", params, body?.toString().orEmpty())
    val headers = kugouAndroidHeaders(params, router)
    val response = if (body == null) PlatformHttp.get(url, cookie, headers)
        else PlatformHttp.postJson(url, body.toString(), cookie, headers)
    return JSONObject(response.text)
}

/** 显式全局标识优先；非数字旧标识也只能携带真实 global 字段后进入新协议。 */
internal fun SourceCollection.kugouGlobalCollectionId(): String? {
    val metadata = runCatching { JSONObject(payload.encoded) }.getOrElse { JSONObject() }
    val remote = identity.remoteId
    if (remote.startsWith("global:")) return remote.removePrefix("global:").takeIf(String::isNotBlank)
    if (stableId.startsWith("kugou-global:")) return stableId.removePrefix("kugou-global:").takeIf(String::isNotBlank)
    if (remote.startsWith("album:") || remote.startsWith("cloudlist:") || remote.toLongOrNull() != null) return null
    return metadata.optString("global_collection_id").takeIf { it.isNotBlank() && it == remote }
}
