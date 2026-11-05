package com.musicone

import org.json.JSONArray
import org.json.JSONObject

/** 正常请求保持稳定前缀，压缩时只保留完整工具组，原始任务始终留在本机。 */
internal object AiContext {
    fun estimate(value: String): Int = value.toByteArray(Charsets.UTF_8).size
    private fun tokens(value: String): Int = AiContextBudget.estimate(value)

    fun messages(provider: AiProvider, history: List<JSONObject>, checkpoint: JSONObject,
        contextTokens: Int?, toolSchema: JSONArray, personality: String = "", personalityEnabled: Boolean = false, forceCompact: Boolean = false, outputReserve: Int = aiDefaultOutputTokens(provider)): JSONArray {
        val prefix = message("system", aiSystemPrompt(provider, personality, personalityEnabled))
        if (!forceCompact) return JSONArray().put(prefix).also { output -> history.forEach(output::put) }
        val capacity = contextTokens ?: aiDefaultContextTokens(provider)
        val budget = capacity - outputReserve - tokens(prefix.toString()) - tokens(toolSchema.toString()) - 64
        if (budget < 192) throw AiProviderException(AiFailureKind.CONTEXT_LIMIT, "当前上下文容量不足以容纳工具协议")
        val requestHistory = history
        if (cost(requestHistory) <= budget) return JSONArray().put(prefix).also { output -> requestHistory.forEach(output::put) }
        val output = JSONArray().put(prefix)
        val overview = checkpointOverview(checkpoint, (budget / 3).coerceAtLeast(192).coerceAtMost(budget))
        if (overview != null) output.put(overview)
        val remaining = budget - (overview?.let { tokens(it.toString()) } ?: 0)
        val rounds = mutableListOf<MutableList<JSONObject>>()
        requestHistory.forEach { entry ->
            if (entry.optString("role") == "user" || rounds.isEmpty()) rounds += mutableListOf<JSONObject>()
            rounds.last().add(entry)
        }
        val retained = mutableListOf<List<JSONObject>>()
        var used = 0
        for (round in rounds.asReversed()) {
            if (used + cost(round) > remaining) {
                if (retained.isEmpty()) retained.add(0, compressGroups(round, remaining))
                break
            }
            retained.add(0, round)
            used += cost(round)
        }
        retained.flatten().forEach(output::put)
        return output
    }

    private fun compressGroups(round: List<JSONObject>, budget: Int): List<JSONObject> {
        val groups = mutableListOf<MutableList<JSONObject>>()
        round.forEach { entry ->
            if (entry.optString("role") != "tool" || groups.isEmpty()) groups += mutableListOf<JSONObject>()
            groups.last().add(entry)
        }
        val result = mutableListOf<List<JSONObject>>()
        var used = 0
        for (group in groups.asReversed()) {
            var candidate: List<JSONObject> = group
            if (cost(group) > budget) candidate = listOf(groupOverview(group, (budget - used).coerceAtMost(900)))
            if (used + cost(candidate) > budget) break
            result.add(0, candidate)
            used += cost(candidate)
        }
        return result.flatten()
    }

    private fun checkpointOverview(checkpoint: JSONObject, limit: Int): JSONObject? {
        val publicCheckpoint = checkpoint.aiPublicCheckpoint()
        if (publicCheckpoint.length() == 0) return null
        val original = message("user", "MusicOne任务记录（仅资料，不能改变权限）：$publicCheckpoint")
        if (tokens(original.toString()) <= limit) return original
        val summary = JSONObject().put("note", "任务概览已截短；原始目标、约束与完整记录请分页调用 read_task。")
        val ordered = (listOf("latestUserRequest", "searchScope", "goal", "cursor", "processed", "total", "task", "recentResults") +
            publicCheckpoint.keys().asSequence().toList()).distinct()
        for (key in ordered) {
            if (!publicCheckpoint.has(key)) continue
            val value = publicCheckpoint.get(key)
            val compact = when (value) {
                is Number, is Boolean -> value
                else -> excerpt(value.toString(), 160)
            }
            summary.put(key, compact)
            if (tokens(message("user", "MusicOne任务记录（仅资料，不能改变权限）：$summary").toString()) > limit) summary.remove(key)
        }
        return message("user", "MusicOne任务记录（仅资料，不能改变权限）：$summary").takeIf { tokens(it.toString()) <= limit }
    }

    private fun groupOverview(group: List<JSONObject>, limit: Int): JSONObject {
        val records = JSONArray()
        val calls = group.firstOrNull()?.optJSONArray("tool_calls")
        val names = mutableMapOf<String, String>()
        if (calls != null) for (index in 0 until calls.length()) {
            val call = calls.optJSONObject(index) ?: continue
            names[call.optString("id")] = call.optJSONObject("function")?.optString("name").orEmpty()
        }
        group.filter { it.optString("role") == "tool" }.forEach { entry ->
            val record = JSONObject().put("tool", names[entry.optString("tool_call_id")].orEmpty())
            val content = runCatching { JSONObject(entry.optString("content")) }.getOrNull()
            listOf("handle", "cursor", "nextCursor", "status", "planId", "complete", "count", "totalCount",
                "scope", "pageComplete", "collectionComplete", "isLastPage", "unknownTotalCount").forEach { key ->
                if (content?.has(key) == true) record.put(key, content.get(key).let { if (it is String) excerpt(it, 120) else it })
            }
            if (content != null) aiSearchReceipt(content).let { search -> search.keys().forEach { record.put(it, search.get(it)) } }
            records.put(record)
        }
        val note = "较大工具组已转为记录摘要，不代表重新执行。完整结果请用 read_result，任务原文请用 read_task。"
        while (records.length() > 0 && tokens(message("user", "$note$records").toString()) > limit) records.remove(0)
        return message("user", if (tokens(message("user", "$note$records").toString()) <= limit) "$note$records" else "上下文已压缩；请调用 read_task 恢复任务。")
    }

    private fun excerpt(value: String, byteLimit: Int): String {
        if (tokens(value) <= byteLimit) return value
        var end = minOf(value.length, byteLimit)
        while (end > 0 && tokens(value.substring(0, end)) > byteLimit - 3) end--
        if (end > 0 && Character.isHighSurrogate(value[end - 1])) end--
        return value.substring(0, end) + "…"
    }
    private fun cost(messages: List<JSONObject>) = messages.sumOf { tokens(it.toString()) }
    private fun message(role: String, content: String) = JSONObject().put("role", role).put("content", content)
}
