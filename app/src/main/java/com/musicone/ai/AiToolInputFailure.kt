package com.musicone

import org.json.JSONObject

/** 参数与缺页错误必须先恢复输入，同一参数重复请求不会修复它。 */
internal class AiToolInputFailure(val code: String, message: String, val recovery: JSONObject? = null) : IllegalArgumentException(message) {
    fun result(): JSONObject = JSONObject().put("status", "error").put("code", code).put("retryable", false)
        .put("message", message).apply { recovery?.let { put("recovery", it) } }
}

internal fun aiContinuation(tool: String, arguments: JSONObject): JSONObject = JSONObject().put("tool", tool).put("arguments", arguments)

internal fun aiResultOffset(handle: String, cursor: String?, count: Int): Int {
    val first = aiContinuation("read_result", JSONObject().put("handle", handle))
    if (cursor.isNullOrBlank() || cursor == "null") return 0
    val raw = when {
        cursor.startsWith("result:") -> {
            val parts = cursor.removePrefix("result:")
            if (parts.substringBeforeLast(':') != handle) throw AiToolInputFailure("invalid_cursor", "本地游标不属于此结果句柄，请使用recovery重新读取", first)
            parts.substringAfterLast(':')
        }
        '|' in cursor -> {
            if (cursor.substringBefore('|') != handle) throw AiToolInputFailure("invalid_cursor", "本地游标与句柄不一致", first)
            cursor.substringAfter('|')
        }
        else -> cursor
    }
    val offset = raw.toIntOrNull()
    if (offset == null || offset !in 0..count) throw AiToolInputFailure("invalid_cursor",
        "本地结果游标无效：只能续读此句柄已保存的${count}首；远端nextCursor请传给remoteContinuation指定工具", first)
    return offset
}

/** 同一真人任务中，必要读取未恢复前不生成依赖残缺资料的歌单修改。 */
internal class AiReadDependencies(private val checkpoint: JSONObject) {
    private fun state(): JSONObject {
        val turn = checkpoint.optString("activeTurnId")
        if (checkpoint.optString("readDependencyTurn") != turn) checkpoint.put("readDependencyTurn", turn).put("failedReads", JSONObject())
        return checkpoint.optJSONObject("failedReads") ?: JSONObject().also { checkpoint.put("failedReads", it) }
    }
    private fun key(call: AiToolCall): String? {
        val args = runCatching { JSONObject(call.arguments) }.getOrNull() ?: return null
        return when (call.name) {
            "read_result" -> "result:" + args.optString("handle")
            "read_playlist" -> "playlist:" + args.optString("playlistId")
            "remote_tracks" -> "remote:" + args.optString("playlistRef")
            "compare_results" -> "comparison:" + aiToolOperationKey(call.name, call.arguments)
            else -> null
        }
    }
    fun record(call: AiToolCall, output: JSONObject) {
        val key = key(call) ?: return
        val failed = state()
        if (aiToolResultFailed(output)) failed.put(key, JSONObject(output.toString()).put("readArguments", JSONObject(call.arguments))) else failed.remove(key)
        // 比较成功说明该组句柄已由本地完整性校验，可以消除同组比较缺页状态。
        if (call.name == "compare_results" && !aiToolResultFailed(output)) {
            val args = JSONObject(call.arguments)
            val handles = (args.getJSONArray("leftHandles").strings() + args.getJSONArray("rightHandles").strings()).toSet()
            handles.forEach { failed.remove("result:$it") }
            failed.keys().asSequence().filter { it.startsWith("comparison:") }.toList().forEach { prior ->
                val previous = failed.getJSONObject(prior).getJSONObject("readArguments")
                val dependencies = previous.getJSONArray("leftHandles").strings() + previous.getJSONArray("rightHandles").strings()
                if (handles.containsAll(dependencies)) failed.remove(prior)
            }
        }
    }
    fun check(call: AiToolCall) {
        if (call.name !in setOf("prepare_add", "prepare_create_playlist", "prepare_remove")) return
        val failures = state()
        if (failures.length() > 0) throw AiToolInputFailure("incomplete_reads",
            "本任务有必要读取尚未恢复，不能用部分结果生成歌单修改；请先按failedReads的恢复信息补齐",
            JSONObject().put("failedReads", JSONObject(failures.toString())))
    }
}
