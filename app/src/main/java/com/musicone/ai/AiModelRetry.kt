package com.musicone

import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** 模型返回的工具调用如果截断不完整，还没实际执行，可以直接重试；已经执行过的错误由工具日志处理。 */
internal suspend fun retryAiModelResponse(onRetry: (Int) -> Unit, request: suspend () -> AiChatResponse): AiChatResponse {
    var retries = 0
    while (true) {
        try {
            val response = request().also { it.validateToolCalls() }
            if (response.searchError == null) return response
            if (retries == 3) return response.copy(searchError = "原生联网失败，已重试3次：${response.searchError}。已停止本次联网操作")
            onRetry(++retries)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: AiProviderException) {
            if (error.kind !in setOf(AiFailureKind.INVALID_RESPONSE, AiFailureKind.NETWORK, AiFailureKind.TIMEOUT,
                    AiFailureKind.RATE_LIMIT, AiFailureKind.SERVER)) throw error
            if (retries == 3) throw AiProviderException(error.kind, "模型工具调用失败，已重试3次：${error.message}")
            onRetry(++retries)
        }
    }
}

internal fun aiToolHasEffects(name: String): Boolean = name.startsWith("prepare_") ||
    name in setOf("show_tracks", "show_playlist", "play_tracks", "play_playlist", "save_task")

internal fun aiExhaustedToolFailures(result: JSONObject): List<String> = buildList {
    if (result.optBoolean("retriesExhausted") && result.optional("message") != null) add(result.getString("message"))
    result.optJSONArray("sourceResults")?.objects()?.forEach { addAll(aiExhaustedToolFailures(it)) }
}.distinct()
