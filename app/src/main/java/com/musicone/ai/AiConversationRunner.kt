package com.musicone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 聊天协议历史与界面消息分开保存，工具对在暂停、失败和压缩时保持完整。 */
internal class AiConversationRunner(
    private val client: AiProviderClient,
    private val configuration: () -> AiConfiguration,
    private val executor: AiToolExecutor,
    val history: MutableList<JSONObject>,
    val checkpoint: JSONObject,
    private val message: (AiChatMessageUi) -> Unit,
    private val progress: (String) -> Unit,
    private val pending: () -> Boolean,
    private val persist: suspend () -> Unit,
    private val compact: suspend (Int) -> Unit,
) {
    suspend fun run(): Boolean {
        if (repairAiToolHistory(history)) persist()
        val config = configuration()
        val schema = aiToolSchema(config)
        val id = checkpoint.optional("activeTurnId") ?: UUID.randomUUID().toString().also { checkpoint.put("activeTurnId", it) }
        val sameTurn = checkpoint.optString("displayTurnId") == id
        var displayedText = if (sameTurn) checkpoint.optString("displayTurnText") else ""
        var displayedReasoning = if (sameTurn) checkpoint.optString("displayTurnReasoning") else ""
        var reasoningElapsedMs = if (sameTurn) checkpoint.optLong("displayTurnReasoningMs") else 0L
        val steps = (if (sameTurn) checkpoint.optJSONArray("displayTurnProgressSteps")?.let { values ->
            (0 until values.length()).map { values.optString(it) }.filter { it.isNotBlank() }
        }.orEmpty() else emptyList()).toMutableList()
        val citations = (if (sameTurn) checkpoint.optJSONArray("displayTurnCitations")?.let { values ->
            (0 until values.length()).map { values.optString(it) }
        }.orEmpty() else emptyList()).toMutableList()
        var result: String? = null
        fun publish(text: String, reasoning: String, thinking: Boolean, elapsedMs: Long = reasoningElapsedMs) {
            if (text.isBlank() && reasoning.isBlank() && result.isNullOrBlank() && citations.isEmpty() && steps.isEmpty()) return
            message(AiChatMessageUi(id, "assistant", text, citations = citations.toList(), result = result,
                reasoning = reasoning, turnId = id, reasoningElapsedMs = elapsedMs.takeIf { it > 0 }, isThinking = thinking,
                progressSteps = steps.toList()))
        }
        fun addStep(text: String) {
            if (text.isBlank() || steps.lastOrNull() == text) return
            steps += text
            checkpoint.put("displayTurnProgressSteps", JSONArray(steps))
        }
        val capacity = config.contextTokens
        var contextRetries = 0
        var previousCalls = ""
        var repeatedCalls = 0
        val retries = AiToolRetry(checkpoint)
        // 有新分页时持续完成任务；重复请求没有进展才暂停，用户仍可随时停止。
        while (true) {
            currentCoroutineContext().ensureActive()
            var request = AiContext.messages(config.provider, history, checkpoint, capacity, schema, config.personality, config.personalityEnabled, outputReserve = config.maxOutputTokens)
            var requestEstimate = AiContextBudget.size(request, schema)
            if (AiContextBudget.needsCompaction(capacity, AiContextBudget.predicted(checkpoint, config, requestEstimate), config.maxOutputTokens)) {
                compact(AiContextBudget.compactionCapacity(checkpoint, config, capacity, requestEstimate))
                checkpoint.put("taskRecoveryRequired", true)
                checkpoint.remove("contextTokenSample")
                request = AiContext.messages(config.provider, history, checkpoint, capacity, schema, config.personality, config.personalityEnabled, outputReserve = config.maxOutputTokens)
                requestEstimate = AiContextBudget.size(request, schema)
            }
            progress("正在处理请求…")
            var streamed = ""
            var streamedReasoning = ""
            var reasoningStarted: Long? = null
            var reasoningStopped: Long? = null
            val response = try { retryAiModelResponse(onRetry = { attempt ->
                streamed = ""; streamedReasoning = ""; reasoningStarted = null; reasoningStopped = null
                publish(displayedText, displayedReasoning, false)
                progress("模型工具调用未完成，正在重试（$attempt/3）…")
            }) {
                client.chat(config, request, schema) { delta ->
                    if (delta.reasoning.isNotEmpty() && reasoningStarted == null) reasoningStarted = System.nanoTime()
                    if (delta.text.isNotEmpty() && reasoningStarted != null && reasoningStopped == null) reasoningStopped = System.nanoTime()
                    if (delta.text.isNotEmpty() || delta.reasoning.isNotEmpty()) {
                        streamed += delta.text
                        streamedReasoning += delta.reasoning
                        val elapsed = reasoningStarted?.let { ((reasoningStopped ?: System.nanoTime()) - it) / 1_000_000 } ?: 0L
                        publish(join(displayedText, streamed), join(displayedReasoning, streamedReasoning),
                            streamedReasoning.isNotEmpty() && reasoningStopped == null, reasoningElapsedMs + elapsed)
                    }
                }
            } } catch (error: AiProviderException) {
                if (error.kind == AiFailureKind.CONTEXT_LIMIT && contextRetries++ < 2) {
                    compact(AiContextBudget.compactionCapacity(checkpoint, config, capacity, requestEstimate, rejected = true))
                    checkpoint.put("taskRecoveryRequired", true)
                    checkpoint.remove("contextTokenSample")
                    continue
                }
                throw error
            }
            val usage = response.usage
            recordUsage(usage)
            AiContextBudget.record(checkpoint, config, requestEstimate, usage)
            response.validateToolCalls()
            history += response.assistantMessage
            // 仅展示服务实际返回的网页注解。
            citations += response.annotations.objects().mapNotNull { entry ->
                val citation = entry.optJSONObject("url_citation") ?: entry
                citation.optional("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            }.distinct().filterNot { it in citations }
            response.searchError?.let {
                result = join(result.orEmpty(), it)
            }
            // 正文始终留在回答区，执行步骤只记录程序实际执行的操作。
            displayedText = join(displayedText, response.text)
            displayedReasoning = join(displayedReasoning, response.assistantMessage.optString("reasoning_content"))
            reasoningElapsedMs += reasoningStarted?.let { ((reasoningStopped ?: System.nanoTime()) - it) / 1_000_000 } ?: 0L
            checkpoint.put("displayTurnId", id).put("displayTurnText", displayedText)
                .put("displayTurnReasoning", displayedReasoning).put("displayTurnReasoningMs", reasoningElapsedMs)
                .put("displayTurnCitations", JSONArray(citations))
            publish(displayedText, displayedReasoning, false)
            persist()
            if (response.toolCalls.isEmpty()) return response.finishReason == "length"
            val signature = response.toolCalls.joinToString { it.name + ":" + it.arguments }
            repeatedCalls = if (signature == previousCalls) repeatedCalls + 1 else 0
            previousCalls = signature
            for (call in response.toolCalls) {
                currentCoroutineContext().ensureActive()
                val output = if (pending()) JSONObject().put("status", "paused").put("message", "等待原生确认，本调用未执行") else retries.execute(
                    call.name, call.arguments, cacheSuccess = aiToolHasEffects(call.name), retrying = { attempt, reason ->
                        val retryStep = "${toolProgress(call.name).removeSuffix("…")}失败：$reason，正在重试（$attempt/3）…"
                        addStep(retryStep); publish(displayedText, displayedReasoning, false); progress(retryStep); persist()
                    }) { try {
                    val step = toolProgress(call.name)
                    addStep(step)
                    publish(displayedText, displayedReasoning, false)
                    progress(step)
                    executor.execute(call, config)
                } catch (error: CancellationException) { throw error }
                catch (error: AiToolInputFailure) { error.result() }
                catch (error: org.json.JSONException) { AiToolInputFailure("invalid_arguments", "工具参数缺失或类型错误：${error.message}").result() }
                catch (error: IllegalArgumentException) { JSONObject().put("status", "error").put("retryable", false).put("message", error.message) }
                catch (error: Exception) {
                    JSONObject().apply {
                        if (call.name in setOf("search_tracks", "search_playlists")) {
                            checkpoint.optJSONObject("lastSearchAttempt")?.let { attempt ->
                                aiSearchReceipt(attempt).let { receipt -> receipt.keys().forEach { put(it, receipt.get(it)) } }
                            }
                            if (!has("searchInvoked")) put("searchInvoked", false)
                        }
                        put("status", "error").put("message", error.message ?: "工具执行失败")
                    }
                } }
                AiReadDependencies(checkpoint).record(call, output)
                val failures = aiExhaustedToolFailures(output)
                failures.forEach { failure ->
                    addStep(failure)
                    if (!result.orEmpty().contains(failure)) result = join(result.orEmpty(), failure)
                }
                if (failures.isNotEmpty()) publish(displayedText, displayedReasoning, false)
                history += JSONObject().put("role", "tool").put("tool_call_id", call.id).put("content", output.toString())
                persist()
            }
            if (pending()) return false
            if (repeatedCalls >= 2) return true
        }
    }

    private fun join(before: String, after: String): String = when {
        before.isBlank() -> after
        after.isBlank() -> before
        else -> "$before\n\n$after"
    }

    /** 实际用量留在任务后台记录，不生成可见 token 文案。 */
    private fun recordUsage(usage: AiUsage) {
        val total = checkpoint.optJSONObject("actualUsage") ?: JSONObject().also { checkpoint.put("actualUsage", it) }
        mapOf("promptTokens" to usage.promptTokens, "completionTokens" to usage.completionTokens,
            "cachedTokens" to usage.cachedTokens, "totalTokens" to usage.totalTokens,
            "reasoningTokens" to usage.reasoningTokens, "searchCalls" to usage.searchCalls,
            "searchPages" to usage.searchPages).forEach { (key, value) -> total.put(key, total.optLong(key) + value) }
    }

    /** 中断后的工具请求永不自动重放，避免重复播放或生成第二个写入计划。 */
    fun closeInterruptedTools() {
        val unanswered = linkedSetOf<String>()
        history.forEach { item ->
            item.optJSONArray("tool_calls")?.objects()?.forEach { unanswered += it.getString("id") }
            if (item.optString("role") == "tool") unanswered -= item.optString("tool_call_id")
        }
        unanswered.forEach { id -> history += JSONObject().put("role", "tool").put("tool_call_id", id)
            .put("content", "{\"status\":\"interrupted\",\"message\":\"调用在停止或退出时中断，请先检查任务记录，不要假定成功或重复执行\"}") }
    }

    private fun toolProgress(name: String): String = when (name) {
        "search_tracks", "web_search", "web_fetch" -> "正在查找歌曲与资料…"
        "remote_tracks", "read_playlist", "read_result" -> "正在读取这一页歌曲…"
        "compare_results" -> "正在核对完整歌曲集合…"
        "read_playback_queue" -> "正在读取当前播放与队列…"
        "prepare_rename", "prepare_add", "prepare_remove", "prepare_undo" -> "正在生成待确认的操作明细…"
        "read_lyrics" -> "正在读取歌词…"
        "prepare_lyric_annotations", "prepare_write_lyrics", "prepare_remove_lyrics" -> "正在整理歌词…"
        "play_tracks" -> "正在提交播放请求…"
        else -> "正在整理歌单资料…"
    }
}
