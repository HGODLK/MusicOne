package com.musicone

import org.json.JSONArray
import org.json.JSONObject

/** 会话整理器的精简摘录：只保留续接任务需要的进度和定位字段，路由、资格等主对话专用信息不发送。 */
internal object AiMemoryExcerpt {
    private val FIELDS = listOf("status", "count", "totalCount", "complete", "collectionComplete", "planId")
    private val LOCATORS = listOf("handle", "cursor", "nextCursor")

    /** 系统注入的偏好资料已由 previousMusicPreferences 提供，不再重复摘录。 */
    fun injected(text: String) = text.startsWith("MusicOne保存的音乐偏好资料")

    /** 搜索范围只保留实际生效的限定，空列表、默认值和轮次标识全部丢弃。 */
    fun searchScope(raw: String): JSONObject? {
        val scope = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val result = JSONObject()
        for (key in listOf("sourceIds", "excludedIds")) {
            scope.optJSONArray(key)?.takeIf { it.length() > 0 }?.let { result.put(key, it) }
        }
        for (key in listOf("currentOnly", "allowFallback")) if (scope.optBoolean(key)) result.put(key, true)
        return result.takeIf { it.length() > 0 }
    }

    fun tool(name: String, data: JSONObject?, text: (String) -> String): JSONObject {
        val record = JSONObject().put("role", "tool").put("name", name)
        if (data == null) return record
        copy(data, record, FIELDS + LOCATORS, text)
        data.optJSONArray("sourceResults")?.let { sources ->
            val slim = JSONArray()
            for (index in 0 until sources.length()) sources.optJSONObject(index)?.let { source ->
                val item = JSONObject()
                copy(source, item, listOf("sourceId", "count", "handle", "nextCursor"), text)
                // 成功的音源不写状态，只有失败时才带上状态和原因。
                val status = source.optString("status")
                if (status.isNotBlank() && status != "ok") copy(source, item, listOf("status", "message"), text)
                slim.put(item)
            }
            if (slim.length() > 0) record.put("sourceResults", slim)
        }
        return record
    }

    /** 连续的同名工具调用合并为一条：累计次数和条数，句柄、游标以最后一次为准。 */
    fun compact(records: List<JSONObject>): MutableList<JSONObject> {
        val result = mutableListOf<JSONObject>()
        records.forEach { record ->
            val previous = result.lastOrNull()
            if (previous != null && record.optString("role") == "tool" && previous.optString("role") == "tool" &&
                previous.optString("name") == record.optString("name")) {
                val merged = JSONObject(record.toString()).put("calls", previous.optInt("calls", 1) + 1)
                val before = previous.opt("count")
                val current = record.opt("count")
                if (before is Number && current is Number) merged.put("count", before.toLong() + current.toLong())
                result[result.lastIndex] = merged
            } else result += record
        }
        return result
    }

    private fun copy(from: JSONObject, to: JSONObject, keys: List<String>, text: (String) -> String) {
        for (key in keys) if (from.has(key)) {
            val value = from.get(key)
            to.put(key, if (value is Number || value is Boolean) value else text(value.toString()))
        }
    }
}
