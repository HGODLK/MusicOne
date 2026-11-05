package com.musicone

import org.json.JSONArray
import org.json.JSONObject

/** 只保留真实搜索回执，不携带歌曲大列表、私有资料或本地诊断。 */
internal fun aiSearchReceipt(result: JSONObject): JSONObject {
    val receipt = JSONObject()
    if (!result.has("searchInvoked") && !result.has("sourceResults")) return receipt
    for (key in listOf("sourceId", "source", "searchInvoked", "status", "count", "message", "explicitSearchAuthorization",
        "eligibilityReason", "handle", "nextCursor", "routing", "requestedSourceIds")) {
        if (result.has(key)) {
            val value = result.get(key)
            receipt.put(key, if (value is String) value.take(200) else value)
        }
    }
    result.optJSONArray("sourceResults")?.let { sources ->
        receipt.put("sourceResults", JSONArray(sources.objects().map { source ->
            aiSearchReceipt(JSONObject(source.toString()).apply { if (!has("searchInvoked")) put("searchInvoked", JSONObject.NULL) })
        }))
    }
    return receipt
}
