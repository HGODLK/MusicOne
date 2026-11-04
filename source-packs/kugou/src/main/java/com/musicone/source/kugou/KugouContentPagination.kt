package com.musicone.source.kugou

import com.musicone.source.sdk.SourceCursorRequest
import org.json.JSONObject

/** 游标绑定查询、账号和页大小，换号或换分类后不能继续旧请求。 */
internal data class KugouContentPage(val page: Int, val size: Int, val scope: String, val owner: String,
    val previous: String = "", val received: Long = 0) {
    fun next(result: JSONObject, rows: List<JSONObject>): String? {
        val signature = KugouProtocol.md5(rows.joinToString("\n") { it.toString() })
        if (rows.isNotEmpty() && signature == previous) throw PlatformApiException("酷狗重复返回上一页，请重试")
        val data = result.optJSONObject("data")
        val total = listOfNotNull(data, result).firstNotNullOfOrNull { value ->
            listOf("total", "total_count", "totalcount", "Total", "count").firstNotNullOfOrNull { key ->
                value.opt(key)?.toString()?.toLongOrNull()?.takeIf { it >= 0 }
            }
        }
        val consumed = received + rows.size
        val more = if (total != null) consumed < total else rows.size >= size
        if (rows.isEmpty() && more) throw PlatformApiException("酷狗分页响应缺少尚未返回的数据，请重试")
        if (!more) return null
        check(page < Int.MAX_VALUE) { "酷狗分页超出范围" }
        return JSONObject().put("page", page + 1).put("size", size).put("scope", scope)
            .put("owner", owner).put("previous", signature).put("received", consumed).toString()
    }

    companion object {
        fun from(request: SourceCursorRequest, scope: String, owner: String, maxSize: Int? = null): KugouContentPage {
            require(request.limit > 0) { "分页数量必须大于零" }
            val size = maxSize?.let { minOf(request.limit, it) } ?: request.limit
            val cursor = request.cursor ?: return KugouContentPage(1, size, scope, owner)
            val parsed = try { JSONObject(cursor) } catch (_: org.json.JSONException) { throw IllegalArgumentException("酷狗分页游标无效") }
            require(parsed.optString("scope") == scope && parsed.optString("owner") == owner && parsed.optInt("size") == size) {
                "酷狗分页条件或账号已变化，请刷新后重试"
            }
            val page = parsed.optInt("page")
            require(page > 1) { "酷狗分页游标无效" }
            val received = parsed.optLong("received", -1)
            require(received > 0) { "酷狗分页游标无效" }
            return KugouContentPage(page, size, scope, owner, parsed.optString("previous"), received)
        }
    }
}

internal fun KugouAccountRequest.contentOwner(): String = "$generation:${KugouProtocol.md5(cookie)}"
