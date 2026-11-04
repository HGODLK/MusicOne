package com.musicone.source.kugou

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject

internal const val KUGOU_ALBUM_PAGE_SIZE = 50

/** 歌手专辑的发行时间倒序序号，接口不接受其他取值来倒排。 */
internal const val KUGOU_ARTIST_ALBUM_SORT = 1

/** 仅用于内容浏览，不参与播放解析、歌词和账号写入。 */
internal data class KugouContentRequest(
    val path: String,
    val router: String? = "openapi.kugou.com",
    val query: Map<String, String> = emptyMap(),
    val body: JSONObject? = null,
    val tid: String? = null,
    val publicCatalog: Boolean = false,
)

internal fun interface KugouContentTransport {
    suspend fun execute(request: KugouContentRequest, cookie: String): JSONObject
}

internal class KugouContentClient(
    private val transport: KugouContentTransport = KugouContentTransport { request, cookie ->
        runInterruptible(Dispatchers.IO) {
            val values = cookie.cookieValues()
            val params = linkedMapOf(
                "dfid" to values["dfid"].orEmpty().ifBlank { "-" },
                "mid" to values["KUGOU_API_MID"].orEmpty().ifBlank { "-" },
                "uuid" to "-", "appid" to KugouProtocol.appId,
                "clientver" to KugouProtocol.clientVersion,
                "clienttime" to (System.currentTimeMillis() / 1_000).toString(),
            ).apply {
                values["token"]?.takeIf(String::isNotBlank)?.let { put("token", it) }
                values["userid"]?.takeIf { it.isNotBlank() && it != "0" }?.let { put("userid", it) }
                putAll(request.query)
            }
            val body = request.body?.toString().orEmpty()
            val url = KugouProtocol.signedAndroidUrl("https://gateway.kugou.com${request.path}", params, body)
            val headers = kugouAndroidHeaders(params, request.router) +
                request.tid?.let { mapOf("kg-tid" to it) }.orEmpty()
            val response = if (request.body == null) PlatformHttp.get(url, cookie, headers)
                else PlatformHttp.postJson(url, body, cookie, headers)
            if (response.status !in 200..299) throw PlatformApiException("酷狗内容请求失败（HTTP ${response.status}）", response.status)
            JSONObject(response.text)
        }
    },
) {
    suspend fun load(request: KugouContentRequest, cookie: String, requireData: Boolean = true): JSONObject {
        currentCoroutineContext().ensureActive()
        // 公开资料请求无需转发账号会话；账号与媒体鉴权仍由原服务负责。
        val result = try {
            transport.execute(request, if (request.publicCatalog) "" else cookie)
        } catch (failure: Exception) {
            // 同步网络中断可能抛出 IO 异常；任务已取消时恢复协程取消语义。
            currentCoroutineContext().ensureActive()
            throw failure
        }
        currentCoroutineContext().ensureActive()
        requireKugouContentSuccess(result, requireData)
        return result
    }

    fun albumDetailRequest(id: String): KugouContentRequest {
        requireAlbumId(id)
        return KugouContentRequest("/kmr/v2/albums", body = JSONObject()
            .put("data", org.json.JSONArray().put(JSONObject().put("album_id", id))).put("is_buy", 0)
            .put("fields", "album_id,album_name,publish_date,sizable_cover,intro,language,is_publish,heat,type,quality,authors,exclusive,author_name,trans_param"), tid = "255")
    }

    fun albumTracksRequest(id: String, page: Int, size: Int): KugouContentRequest {
        requireAlbumId(id)
        require(page > 0 && size in 1..KUGOU_ALBUM_PAGE_SIZE) { "酷狗专辑每页最多50首，请继续翻页读取" }
        return KugouContentRequest("/v1/album_audio/lite", body = JSONObject().put("album_id", id).put("is_buy", "")
            .put("page", page).put("pagesize", size), tid = "255")
    }

    /** 歌手专辑列表固定按发行时间倒序；sort=3 是热度序，2 是其他平台的混排序。 */
    fun artistAlbumsRequest(id: String, page: Int, size: Int): KugouContentRequest {
        require(id.isNotBlank() && id.all(Char::isDigit) && id.any { it != '0' }) { "酷狗歌手编号无效" }
        require(page > 0 && size > 0) { "酷狗歌手专辑分页无效" }
        return KugouContentRequest("/kmr/v1/author/albums", body = JSONObject()
            .put("author_id", id).put("page", page).put("pagesize", size)
            .put("sort", KUGOU_ARTIST_ALBUM_SORT).put("category", 1).put("area_code", "all"), tid = "36")
    }

    private fun requireAlbumId(id: String) {
        require(id.isNotBlank() && id.all(Char::isDigit) && id.any { it != '0' }) { "酷狗专辑编号无效" }
    }

    fun searchRequest(query: String, kind: com.musicone.source.sdk.SourceSearchKind, page: Int, size: Int): KugouContentRequest {
        val type = when (kind) {
            com.musicone.source.sdk.SourceSearchKind.TRACK -> "song"
            com.musicone.source.sdk.SourceSearchKind.COLLECTION -> "special"
            com.musicone.source.sdk.SourceSearchKind.ARTIST -> "author"
            com.musicone.source.sdk.SourceSearchKind.ALBUM -> "album"
            else -> throw UnsupportedOperationException("酷狗未接入此搜索类型")
        }
        val params = linkedMapOf("keyword" to query, "page" to page.toString(), "pagesize" to size.toString(),
            "platform" to "AndroidFilter", "iscorrection" to "1")
        when (type) {
            "song" -> params.putAll(mapOf("privilegefilter" to "0", "area_code" to "1", "dopicfull" to "1"))
            "album" -> params.putAll(mapOf("category" to "1", "sorttype" to "0", "searchsong" to "0"))
            else -> params.putAll(mapOf("albumhide" to "0", "nocollect" to "0"))
        }
        return KugouContentRequest("/${if (type == "song") "v2" else "v1"}/search/$type", "complexsearch.kugou.com", params)
    }
}

internal fun requireKugouContentSuccess(result: JSONObject, requireData: Boolean = true) {
    val code = result.optInt("error_code", result.optInt("errcode", 0))
    if ((result.has("status") && result.optInt("status", -1) != 1) || code != 0) {
        // 不把 status=0 或登录失效响应当成正常空列表。
        throw PlatformApiException(if (code != 0) "酷狗内容加载失败（错误码 $code）" else "酷狗内容加载失败，请重试", code.takeIf { it != 0 })
    }
    if (requireData && !result.has("data")) throw PlatformApiException("酷狗内容响应缺少数据")
}
