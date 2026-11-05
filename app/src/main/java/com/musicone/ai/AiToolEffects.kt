package com.musicone

import org.json.JSONObject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 副作用完成后保存阶段收据；异常中断后只核对收据，不盲目重放播放或计划。 */
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
            // 计划生成由数据库事务原子提交；异常会回滚，可重试，成功先记录再响应取消。
            try { action().also { started.put("result", JSONObject(it.toString())) } }
            catch (error: Exception) { ledger.remove(key); throw error }
        } else action().also {
            if (aiToolResultFailed(it)) ledger.remove(key) else started.put("result", JSONObject(it.toString()))
        }
    }
}
