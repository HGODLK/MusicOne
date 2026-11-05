package com.musicone

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject

class AiProviderClient(private val store: AiConfigurationStore) {
    suspend fun fetchModels(provider: AiProvider, apiKeyOverride: String? = null, baseUrlOverride: String? = null): AiModelRefresh {
        val json = request(provider, "/models", null, apiKeyOverride, baseUrlOverride) { connection -> readJson(connection) }
        val result = AiProviderProtocol.models(json, store.selectedModel(provider))
        return if (store.requiresModelSelection(provider)) result.copy(selectedModelId = "") else result
    }

    suspend fun chat(
        configuration: AiConfiguration,
        messages: JSONArray,
        tools: JSONArray = JSONArray(),
        maxOutputTokens: Int? = null,
        onDelta: suspend (AiChatDelta) -> Unit = {}
    ): AiChatResponse {
        // 仅构造协议字段，密钥始终只出现在认证请求头。
        val body = aiChatRequest(configuration,
            store.models(configuration.provider).firstOrNull { it.id == configuration.modelId }, messages, tools)
        maxOutputTokens?.let {
            require(it > 0 && it <= configuration.maxOutputTokens) { "输出覆盖值超出当前配置" }
            body.put("max_tokens", it)
        }
        return request(configuration.provider, "/chat/completions", body, baseUrlOverride = configuration.baseUrl.takeIf { configuration.provider == AiProvider.OTHER }) { connection ->
            val accumulator = AiStreamAccumulator()
            val contentType = connection.contentType.orEmpty()
            if (!contentType.contains("text/event-stream")) {
                val json = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                if (json.has("error")) throw AiProviderProtocol.failure(500, json)
                onDelta(accumulator.accept(json))
            } else {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val event = StringBuilder()
                    var completed = false
                    suspend fun deliver(): Boolean {
                        val data = event.toString().trim()
                        event.clear()
                        if (data == "[DONE]") { completed = true; return false }
                        if (data.isNotEmpty()) {
                            val json = JSONObject(data)
                            if (json.has("error")) throw AiProviderProtocol.failure(500, json)
                            onDelta(accumulator.accept(json))
                        }
                        return true
                    }
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) {
                            if (!deliver()) break
                        } else if (line.startsWith("data:")) {
                            if (event.isNotEmpty()) event.append('\n')
                            event.append(line.substring(5).trimStart())
                        }
                    }
                    if (event.isNotEmpty()) deliver()
                    if (!completed) throw AiProviderException(AiFailureKind.NETWORK, "响应连接提前中断，请重试")
                }
            }
            accumulator.response()
        }
    }

    fun webSearchAvailable(): Boolean = store.webSearchAvailable()

    /** 手动模型不依赖模型列表接口，使用不含用户资料的最小请求验证连接。 */
    suspend fun verifyModel(baseUrl: String, key: String, modelId: String) {
        require(modelId.isNotBlank()) { "请填写模型名称" }
        val body = aiChatRequest(AiConfiguration(AiProvider.OTHER, modelId), null,
            JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK.")), JSONArray())
            .put("stream", false).put("max_tokens", 16)
            .apply { remove("stream_options") }
        request(AiProvider.OTHER, "/chat/completions", body, key, baseUrl) { connection ->
            val json = readJson(connection)
            if (json.has("error")) throw AiProviderProtocol.failure(500, json)
            if (json.optJSONArray("choices")?.length() != 1) throw AiProviderException(AiFailureKind.INVALID_RESPONSE, "模型未返回有效对话结果")
        }
    }

    suspend fun deepSeekSearch(query: String, count: Int = 5): JSONObject {
        require(query.isNotBlank() && query.codePointCount(0, query.length) <= 128) { "搜索词需为 1 至 128 个字符" }
        require(count in 1..50)
        return request(AiProvider.DEEPSEEK, "/v1/tools/search", JSONObject().put("query", query).put("count", count)) { readJson(it) }
    }

    suspend fun deepSeekFetch(url: String): JSONObject {
        val uri = runCatching { URI(url) }.getOrNull()
        require(url.length <= 8192 && uri != null && uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "网页地址必须为不带凭证的 HTTP 或 HTTPS 地址"
        }
        return request(AiProvider.DEEPSEEK, "/v1/tools/fetch", JSONObject().put("url", url)) { readJson(it) }
    }

    private fun readJson(connection: HttpURLConnection): JSONObject =
        JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })

    private suspend fun <T> request(
        provider: AiProvider,
        path: String,
        body: JSONObject?,
        apiKeyOverride: String? = null,
        baseUrlOverride: String? = null,
        consume: suspend (HttpURLConnection) -> T
    ): T = suspendCancellableCoroutine { continuation ->
        val activeConnection = AtomicReference<HttpURLConnection?>()
        val worker = CoroutineScope(Dispatchers.IO).launch {
            var authenticatingStoredKey = apiKeyOverride == null
            try {
                val key = apiKeyOverride?.trim() ?: store.apiKey(provider)
                val baseUrl = aiProviderEndpoint(provider, baseUrlOverride ?: store.baseUrl(provider))
                authenticatingStoredKey = runCatching { store.apiKey(provider) == key && store.baseUrl(provider) == baseUrl }.getOrDefault(false)
                if (key.isBlank() && provider == AiProvider.DEEPSEEK) throw AiProviderException(AiFailureKind.CONFIGURATION, "请先填写 API Key")
                val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
                activeConnection.set(connection)
                currentCoroutineContext().ensureActive()
                connection.connectTimeout = 20_000
                connection.readTimeout = 120_000
                // 禁止跟随重定向，避免认证头被转发至其他地址。
                connection.instanceFollowRedirects = false
                connection.requestMethod = if (body == null) "GET" else "POST"
                connection.setRequestProperty("Accept", "application/json, text/event-stream")
                if (key.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $key")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                if (status !in 200..299) {
                    val error = runCatching { JSONObject(connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()) }.getOrNull()
                    throw AiProviderProtocol.failure(status, error)
                }
                val result = consume(connection)
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Exception) {
                val mapped = when (error) {
                    is AiProviderException, is CancellationException -> error
                    is SocketTimeoutException -> AiProviderException(AiFailureKind.TIMEOUT, "网络请求超时，请重试")
                    is IOException -> AiProviderException(AiFailureKind.NETWORK, "网络连接失败，请检查网络")
                    else -> AiProviderException(AiFailureKind.INVALID_RESPONSE, "AI 服务返回了无法解析的数据")
                }
                if (authenticatingStoredKey && (mapped as? AiProviderException)?.kind == AiFailureKind.AUTHENTICATION) store.invalidate(provider)
                if (continuation.isActive) continuation.resumeWithException(mapped)
            } finally {
                activeConnection.getAndSet(null)?.disconnect()
            }
        }
        continuation.invokeOnCancellation {
            activeConnection.getAndSet(null)?.disconnect()
            worker.cancel()
        }
    }
}
