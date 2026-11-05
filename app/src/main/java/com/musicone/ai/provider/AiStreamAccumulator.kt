package com.musicone

import org.json.JSONArray
import org.json.JSONObject

/** 按索引拼接流式函数名和参数，保留后续工具轮次所需的推理历史。 */
class AiStreamAccumulator {
    private val content = StringBuilder()
    private val reasoning = StringBuilder()
    private val calls = sortedMapOf<Int, JSONObject>()
    private val annotations = JSONArray()
    private var usage = AiUsage()
    private var finishReason = ""
    private var searchError: String? = null

    fun accept(chunk: JSONObject): AiChatDelta {
        if (chunk.has("usage") && !chunk.isNull("usage")) usage = AiProviderProtocol.usage(chunk.optJSONObject("usage"))
        val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: return AiChatDelta()
        if (!choice.isNull("finish_reason")) finishReason = choice.optString("finish_reason")
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return AiChatDelta()
        val text = if (delta.isNull("content")) "" else delta.optString("content")
        val thought = if (delta.isNull("reasoning_content")) "" else delta.optString("reasoning_content")
        content.append(text)
        reasoning.append(thought)
        delta.optJSONArray("annotations")?.let { values ->
            for (index in 0 until values.length()) annotations.put(values.get(index))
        }
        delta.optString("error_message").takeIf { it.isNotBlank() && it != "null" }?.let { searchError = it }
        chunk.optString("error_message").takeIf { it.isNotBlank() && it != "null" }?.let { searchError = it }
        delta.optJSONArray("tool_calls")?.let { values ->
            for (index in 0 until values.length()) {
                val part = values.optJSONObject(index) ?: continue
                val target = calls.getOrPut(part.optInt("index", index)) {
                    JSONObject().put("id", "").put("type", "function")
                        .put("function", JSONObject().put("name", "").put("arguments", ""))
                }
                part.optString("id").takeIf { it.isNotBlank() }?.let { target.put("id", it) }
                val function = part.optJSONObject("function") ?: continue
                val accumulated = target.getJSONObject("function")
                for (field in listOf("name", "arguments")) {
                    if (!function.isNull(field)) accumulated.put(field, accumulated.optString(field) + function.optString(field))
                }
            }
        }
        // 原样转发供应商实际返回的思考，同时在协议历史中保留完整内容。
        return AiChatDelta(text, thought)
    }

    fun response(): AiChatResponse {
        val message = JSONObject().put("role", "assistant").put("content", content.toString())
        if (reasoning.isNotEmpty()) message.put("reasoning_content", reasoning.toString())
        val tools = JSONArray()
        calls.values.forEach { tools.put(it) }
        if (tools.length() > 0) message.put("tool_calls", tools)
        if (annotations.length() > 0) message.put("annotations", annotations)
        return AiChatResponse(message, calls.values.map { call ->
            val function = call.getJSONObject("function")
            AiToolCall(call.optString("id"), function.optString("name"), function.optString("arguments"))
        }, usage, annotations, finishReason, searchError)
    }
}
