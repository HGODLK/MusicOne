package com.musicone

import org.json.JSONObject
import org.json.JSONTokener

/** 参数在进入协议历史前检查，截断调用不得执行或在下一次请求中重发。 */
private fun completeAiArguments(arguments: String): Boolean = runCatching {
    val parser = JSONTokener(arguments)
    parser.nextValue() is JSONObject && parser.nextClean() == '\u0000'
}.getOrDefault(false)

internal fun AiToolCall.hasCompleteArguments(): Boolean =
    id.isNotBlank() && name.isNotBlank() && completeAiArguments(arguments)

internal fun AiChatResponse.validateToolCalls() {
    if (toolCalls.isEmpty()) return
    if (finishReason == "length") throw AiProviderException(AiFailureKind.INVALID_RESPONSE,
        "工具调用因输出长度限制中断，本批未执行，请重试。整批导入可使用结果句柄。")
    if (toolCalls.any { !it.hasCompleteArguments() } || toolCalls.map { it.id }.distinct().size != toolCalls.size) {
        throw AiProviderException(AiFailureKind.INVALID_RESPONSE, "AI返回的工具调用不完整，本批未执行，请重试")
    }
}

/** 修复旧版本已保存的残缺调用，只移除无法解析且不可能执行成功的工具参数。 */
internal fun repairAiToolHistory(history: MutableList<JSONObject>): Boolean {
    val invalidIds = mutableSetOf<String>()
    var changed = false
    val repaired = history.mapNotNull { entry ->
        if (entry.optString("role") == "tool" && entry.optString("tool_call_id") in invalidIds) return@mapNotNull null
        val calls = entry.optJSONArray("tool_calls") ?: return@mapNotNull entry
        val retained = calls.objects().filter { call ->
            val function = call.optJSONObject("function")
            // 旧版允许空参数按空对象执行，不能把这类可能已成功的调用删除后重放。
            val valid = completeAiArguments(function?.optString("arguments").orEmpty().ifBlank { "{}" })
            if (!valid) { invalidIds += call.optString("id"); changed = true }
            valid
        }
        if (retained.size == calls.length()) entry else JSONObject(entry.toString()).apply {
            if (retained.isEmpty()) remove("tool_calls") else put("tool_calls", org.json.JSONArray(retained))
        }.takeIf { retained.isNotEmpty() || it.optString("content").isNotBlank() }
    }
    if (changed) { history.clear(); history.addAll(repaired) }
    return changed
}
