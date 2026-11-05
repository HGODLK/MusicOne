package com.musicone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** 重试按本轮操作与参数记账，换调用编号或恢复任务不能重置失败次数。 */
internal class AiToolRetry(private val checkpoint: JSONObject) {
    suspend fun execute(name: String, arguments: String, cacheSuccess: Boolean = false,
        retrying: suspend (Int, String) -> Unit = { _, _ -> }, action: suspend () -> JSONObject): JSONObject {
        val key = aiToolOperationKey(name, arguments)
        val turn = checkpoint.optString("activeTurnId")
        if (checkpoint.optString("toolRetryTurn") != turn) {
            checkpoint.put("toolRetryTurn", turn).put("toolRetryLedger", JSONObject())
        }
        val ledger = checkpoint.optJSONObject("toolRetryLedger") ?: JSONObject().also { checkpoint.put("toolRetryLedger", it) }
        val entry = ledger.optJSONObject(key) ?: JSONObject().put("attempts", 0).also { ledger.put(key, it) }
        entry.optJSONObject("result")?.let { return JSONObject(it.toString()) }
        while (entry.optInt("attempts") < 4) {
            currentCoroutineContext().ensureActive()
            val attempt = entry.optInt("attempts")
            if (attempt > 0) retrying(attempt, entry.optString("reason"))
            val result = try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: AiToolInputFailure) { error.result() }
            catch (error: org.json.JSONException) { AiToolInputFailure("invalid_arguments", "工具参数缺失或类型错误：${error.message}").result() }
            catch (error: IllegalArgumentException) { JSONObject().put("retryable", false).put("status", "error").put("message", error.message) }
            catch (error: Exception) { JSONObject().put("status", "error").put("message", error.message ?: "工具执行失败") }
            if (result.optString("status") in setOf("paused", "awaiting_confirmation", "cancelled", "interrupted")) return result
            entry.put("attempts", attempt + 1)
            if (!aiToolResultFailed(result)) {
                if (cacheSuccess) entry.put("result", result) else ledger.remove(key)
                return result
            }
            if (result.has("retryable") && !result.optBoolean("retryable")) {
                ledger.remove(key)
                return result
            }
            if (result.optBoolean("retriesExhausted")) { entry.put("result", result); return result }
            entry.put("reason", result.optString("message").ifBlank { result.optString("error").ifBlank { "工具返回失败状态" } })
            if (attempt == 3) {
                result.put("retriesExhausted", true).put("retryCount", 3)
                    .put("message", "$name 失败，已重试3次：${entry.optString("reason")}。已停止此操作。")
                entry.put("result", result)
                return result
            }
        }
        return entry.getJSONObject("result")
    }
}

internal fun aiToolResultFailed(result: JSONObject): Boolean = result.optString("status").lowercase() in
    setOf("error", "failed", "failure") || result.opt("error")?.let { it != JSONObject.NULL && it != false && it.toString().isNotBlank() } == true

/** 参数对象键排序后生成稳定编号，不保存第二份敏感参数。 */
internal fun aiToolOperationKey(name: String, arguments: String): String {
    fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }
    val normalized = runCatching { canonical(JSONObject(arguments.ifBlank { "{}" })) }.getOrDefault(arguments.trim())
    return MessageDigest.getInstance("SHA-256").digest((name + ":" + normalized).toByteArray()).joinToString("") { "%02x".format(it) }
}
