package com.musicone

import org.json.JSONArray
import org.json.JSONObject

/** 按用户设置发送思考和输出参数；工具续接完整保留供应商返回的协议历史。 */
internal fun aiChatRequest(configuration: AiConfiguration, model: AiModel?, messages: JSONArray, tools: JSONArray): JSONObject {
    if (configuration.modelId.isBlank()) throw AiProviderException(AiFailureKind.CONFIGURATION, "请先选择模型")
    require(aiRequestOptionsError(configuration.contextTokens.toString(), configuration.maxOutputTokens.toString(),
        configuration.thinkingEnabled, configuration.reasoningEffort) == null) { "请求容量或思考强度配置无效" }
    val thinking = configuration.thinkingEnabled
    val protocolMessages = JSONArray(messages.toString())
    for (index in 0 until protocolMessages.length()) protocolMessages.optJSONObject(index)?.apply {
        remove("musiconeTurnId"); remove("musiconeSentAt")
    }
    val body = JSONObject().put("model", configuration.modelId).put("messages", protocolMessages)
        .put("stream", true).put("max_tokens", configuration.maxOutputTokens)
    body.put("thinking", JSONObject().put("type", if (thinking) "enabled" else "disabled"))
    if (thinking) body.put("reasoning_effort", configuration.reasoningEffort.trim())
    body.put("stream_options", JSONObject().put("include_usage", true))
    val requestTools = JSONArray(tools.toString())
    if (requestTools.length() > 0) body.put("tools", requestTools)
    return body
}
