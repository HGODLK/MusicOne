package com.musicone

import org.json.JSONArray
import org.json.JSONObject

enum class AiProvider(val label: String, internal val baseUrl: String) {
    DEEPSEEK("DeepSeek", "https://api.deepseek.com"),
    OTHER("其他", "")
}

data class AiConfiguration(
    val provider: AiProvider = AiProvider.DEEPSEEK,
    val modelId: String = "",
    val webSearchEnabled: Boolean = false,
    val thinkingEnabled: Boolean = false,
    val personality: String = "",
    val personalityEnabled: Boolean = false,
    val baseUrl: String = "",
    val contextTokens: Int = aiDefaultContextTokens(provider),
    val maxOutputTokens: Int = aiDefaultOutputTokens(provider),
    val reasoningEffort: String = "low"
)

data class AiModel(val id: String, val name: String = id, val contextTokens: Int? = null,
    val supportsThinking: Boolean = false, val supportsLowThinking: Boolean = false)
data class AiModelRefresh(val models: List<AiModel>, val selectedModelId: String)
data class AiToolCall(val id: String, val name: String, val arguments: String)
data class AiUsage(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
    val totalTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val searchCalls: Long = 0,
    val searchPages: Long = 0
)
data class AiChatDelta(val text: String = "", val reasoning: String = "")
data class AiChatResponse(
    val assistantMessage: JSONObject,
    val toolCalls: List<AiToolCall>,
    val usage: AiUsage,
    val annotations: JSONArray,
    val finishReason: String,
    val searchError: String? = null
) {
    val text: String get() = assistantMessage.optString("content")
}

enum class AiFailureKind { AUTHENTICATION, PERMISSION, TIMEOUT, RATE_LIMIT, BALANCE, NETWORK, SERVER, INVALID_RESPONSE, CONFIGURATION, CONTEXT_LIMIT }
class AiProviderException(val kind: AiFailureKind, message: String, val httpStatus: Int? = null,
    val providerCode: String? = null) : Exception(message)

object AiProviderProtocol {
    /** 小米模型列表可能省略窗口信息，已确认的 MiMo 2.6 文字模型使用 1M 上下文。 */
    fun contextTokens(id: String, reported: Int?): Int? = reported?.takeIf { it > 0 } ?: when (id) {
        "mimo-v2.6-flash", "mimo-v2.6-pro", "mimo-v2.6-pro-ultraspeed" -> 1_048_576
        else -> null
    }

    fun models(json: JSONObject, selected: String): AiModelRefresh {
        val data = json.optJSONArray("data") ?: throw AiProviderException(AiFailureKind.INVALID_RESPONSE, "模型列表格式异常")
        val models = (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").trim()
            val lower = id.lowercase()
            val output = item.optJSONArray("output_modalities")
            val textOutput = output == null || (0 until output.length()).any { output.optString(it) == "text" }
            if (id.isBlank() || !textOutput || Regex("(^|[-_])(tts|asr|embedding|speech)([-_]|$)").containsMatchIn(lower)) null
            else AiModel(id, item.optString("name", id), contextTokens(id, item.optInt("context_window")),
                item.optJSONObject("effort")?.optJSONArray("supported_levels")?.length()?.let { it > 0 } == true ||
                    id in setOf("deepseek-flash", "deepseek-v4-pro", "mimo-v2.6-flash", "mimo-v2.6-pro", "mimo-v2.6-pro-ultraspeed"),
                supportsLowThinking = item.optJSONObject("effort")?.optJSONArray("supported_levels")?.let { levels ->
                    (0 until levels.length()).any { levels.optString(it) == "low" }
                } ?: (id in setOf("deepseek-flash", "deepseek-v4-pro")))
        }.distinctBy { it.id }
        return AiModelRefresh(models, if (selected.isBlank()) models.firstOrNull()?.id.orEmpty()
            else selected.takeIf { id -> models.any { it.id == id } }.orEmpty())
    }

    fun usage(json: JSONObject?): AiUsage {
        if (json == null) return AiUsage()
        return AiUsage(json.optLong("prompt_tokens"), json.optLong("completion_tokens"),
            json.optLong("prompt_cache_hit_tokens", json.optJSONObject("prompt_tokens_details")?.optLong("cached_tokens") ?: 0),
            json.optLong("total_tokens"), json.optJSONObject("completion_tokens_details")?.optLong("reasoning_tokens") ?: 0,
            json.optJSONObject("web_search_usage")?.optLong("tool_usage") ?: 0,
            json.optJSONObject("web_search_usage")?.optLong("page_usage") ?: 0)
    }

    fun failure(status: Int, body: JSONObject?): AiProviderException {
        val error = body?.optJSONObject("error")
        val code = error?.optString("code").orEmpty().lowercase()
        val message = error?.optString("message").orEmpty().lowercase()
        val kind = when {
            "context_length" in code || "context length" in message || "maximum context" in message || "上下文" in message -> AiFailureKind.CONTEXT_LIMIT
            status == 401 -> AiFailureKind.AUTHENTICATION
            status == 403 -> AiFailureKind.PERMISSION
            status == 402 || listOf("balance", "quota", "credit", "余额", "额度").any { it in code || it in message } -> AiFailureKind.BALANCE
            status == 429 -> AiFailureKind.RATE_LIMIT
            status == 408 || status == 504 -> AiFailureKind.TIMEOUT
            else -> AiFailureKind.SERVER
        }
        // 不回显服务端原文，避免意外泄漏凭证或请求参数。
        val display = when (kind) {
            AiFailureKind.AUTHENTICATION -> "API Key 无效或没有访问权限"
            AiFailureKind.PERMISSION -> "当前账户没有该模型或服务的访问权限"
            AiFailureKind.BALANCE -> "账户余额或可用额度不足"
            AiFailureKind.RATE_LIMIT -> "请求过于频繁，请稍后重试"
            AiFailureKind.TIMEOUT -> "服务响应超时，请重试"
            AiFailureKind.CONTEXT_LIMIT -> "对话内容超出模型上下文限制"
            else -> "AI 服务请求失败（HTTP $status）"
        }
        val safeCode = code.takeIf { it.matches(Regex("[a-z0-9_.-]{1,64}")) }
        val detail = if (kind == AiFailureKind.SERVER && safeCode != null) "$display，错误码：$safeCode" else display
        return AiProviderException(kind, detail, status, safeCode)
    }
}
