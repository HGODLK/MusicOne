package com.musicone

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiBatchImportTest {
    @Test fun batchSelectionUsesTwoHandlesForAll121Songs() {
        val selection = AiTrackSelection.parse(JSONObject().put("resultHandles", JSONArray(listOf("page-100", "page-21", "page-100"))))
        assertEquals(listOf("page-100", "page-21"), selection.resultHandles)
        assertTrue(selection.trackRefs.isEmpty())
        assertTrue(AiTrackSelection.parse(JSONObject().put("trackRefs", JSONArray(listOf("real:0")))).resultHandles.isEmpty())
    }

    @Test fun invalidOrAmbiguousSelectionsAreRejected() {
        listOf(JSONObject(), JSONObject().put("resultHandles", JSONArray()),
            JSONObject().put("resultHandles", JSONArray().put(17)),
            JSONObject().put("resultHandles", JSONArray().put(" ")),
            JSONObject().put("resultHandles", JSONArray().put("page")).put("trackRefs", JSONArray().put("real:0")))
            .forEach { args -> try { AiTrackSelection.parse(args); fail("必须拒绝无效选择") } catch (_: IllegalArgumentException) { } }
    }

    @Test fun prepareToolsKeepBothExplicitAndWholeResultSelectionWithoutDirectWrite() {
        val tools = aiToolSchema(AiConfiguration()).objects().map { it.getJSONObject("function") }
        listOf("prepare_add", "prepare_create_playlist").forEach { name ->
            val parameters = tools.single { it.getString("name") == name }.getJSONObject("parameters")
            assertTrue(parameters.getJSONObject("properties").has("resultHandles"))
            assertTrue(parameters.getJSONObject("properties").has("trackRefs"))
            assertFalse(parameters.getJSONArray("required").strings().contains("trackRefs"))
        }
        assertFalse(tools.any { it.getString("name") == "confirm" })
    }

    @Test fun lengthFinishRejectsEvenSyntacticallyCompleteToolsBeforeHistoryOrExecution() {
        val response = response("length", "{}")
        try { response.validateToolCalls(); fail("截断响应不能执行") } catch (error: AiProviderException) {
            assertEquals(AiFailureKind.INVALID_RESPONSE, error.kind)
        }
    }

    @Test fun incompleteToolsAndTrailingJsonAreRejectedButCompleteToolsPass() {
        response("tool_calls", "{\"resultHandles\":[\"page\"]}").validateToolCalls()
        listOf("{\"trackRefs\":[", "{}{}", "[]", "").forEach { args ->
            try { response("tool_calls", args).validateToolCalls(); fail("残缺参数不能续发") } catch (_: AiProviderException) { }
        }
        assertFalse(AiToolCall("", "prepare_add", "{}").hasCompleteArguments())
        assertFalse(AiToolCall("id", "", "{}").hasCompleteArguments())
    }

    @Test fun repairOldHistoryRemovesOnlyBrokenCallsAndTheirToolReplies() {
        val valid = call("valid", "")
        val broken = call("broken", "{\"trackRefs\":[")
        val assistant = JSONObject().put("role", "assistant").put("content", "准备导入")
            .put("reasoning_content", "保留原推理").put("tool_calls", JSONArray().put(valid).put(broken))
        val validReply = JSONObject().put("role", "tool").put("tool_call_id", "valid").put("content", "已执行")
        val history = mutableListOf(assistant, validReply,
            JSONObject().put("role", "tool").put("tool_call_id", "broken").put("content", "参数错误"))
        assertTrue(repairAiToolHistory(history))
        assertEquals(2, history.size)
        assertEquals("valid", history.first().getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("保留原推理", history.first().getString("reasoning_content"))
        assertSame(validReply, history.last())
        assertFalse(repairAiToolHistory(history))
    }

    @Test fun safeProviderErrorCodeIsAvailableWithoutEchoingMessageOrRequest() {
        val failure = AiProviderProtocol.failure(400, JSONObject().put("error",
            JSONObject().put("code", "invalid_tool_call").put("message", "private request and key")))
        assertEquals("invalid_tool_call", failure.providerCode)
        assertTrue(failure.message!!.contains("invalid_tool_call"))
        assertFalse(failure.message!!.contains("private"))
        assertNull(AiProviderProtocol.failure(400, JSONObject().put("error",
            JSONObject().put("code", "contains secret / text"))).providerCode)
    }

    private fun call(id: String, arguments: String) = JSONObject().put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", "prepare_add").put("arguments", arguments))

    private fun response(reason: String, arguments: String) = AiChatResponse(
        JSONObject().put("role", "assistant"), listOf(AiToolCall("call", "prepare_add", arguments)),
        AiUsage(), JSONArray(), reason)
}
