package com.musicone

import org.json.JSONObject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 带副作用的工具执行完就记下结果；异常中断后先查记录，不盲目重放起播或重复建计划。 */
internal class AiToolEffects(private val checkpoint: JSONObject) {
    private fun ledger(): JSONObject {
        val turn = checkpoint.optString("activeTurnId")
        if (checkpoint.optString("effectTurn") != turn) checkpoint.put("effectTurn", turn).put("effectLedger", JSONObject())
        return checkpoint.optJSONObject("effectLedger") ?: JSONObject().also { checkpoint.put("effectLedger", it) }
    }
    fun saved(call: AiToolCall): JSONObject? = ledger().optJSONObject(aiToolOperationKey(call.name, call.arguments))?.optJSONObject("result")
    fun save(call: AiToolCall, result: JSONObject) {
        ledger().put(aiToolOperationKey(call.name, call.arguments), JSONObject().put("result", JSONObject(result.toString())))
    }
    suspend fun stage(call: AiToolCall, phase: String, transactional: Boolean = false, action: suspend () -> JSONObject): JSONObject {
        val ledger = ledger()
        val key = aiToolOperationKey(call.name + ":" + phase, call.arguments)
        val entry = ledger.optJSONObject(key)
        entry?.optJSONObject("result")?.let { return JSONObject(it.toString()) }
        check(entry == null) { "${call.name} 的${phase}结果尚未确认，重试核对未发现成功收据，为避免重复操作已停止提交" }
        val started = JSONObject().put("started", true)
        ledger.put(key, started)
        return if (transactional) withContext(NonCancellable) {
            // 生成计划走数据库事务，失败会自动回滚可以重试；成功后先记入台账再响应取消。
            try { action().also { started.put("result", JSONObject(it.toString())) } }
            catch (error: Exception) { ledger.remove(key); throw error }
        } else action().also {
            if (aiToolResultFailed(it)) ledger.remove(key) else started.put("result", JSONObject(it.toString()))
        }
    }
}
