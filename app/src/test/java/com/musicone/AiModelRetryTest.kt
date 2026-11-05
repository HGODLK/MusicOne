package com.musicone

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiModelRetryTest {
    private fun response(calls: List<AiToolCall> = emptyList(), searchError: String? = null) =
        AiChatResponse(JSONObject().put("content", "推荐正文"), calls, AiUsage(), JSONArray(), "stop", searchError)

    @Test fun incompleteCallsAreRequestedAgainBeforeAnyExecution() = runBlocking {
        var attempts = 0
        val retries = mutableListOf<Int>()
        val answer = retryAiModelResponse({ retries += it }) {
            attempts++
            if (attempts < 4) response(listOf(AiToolCall("call-$attempts", "read", "{")))
            else response()
        }
        assertEquals(4, attempts)
        assertEquals(listOf(1, 2, 3), retries)
        assertEquals("推荐正文", answer.text)
    }
    @Test fun permanentlyInvalidCallsStopAfterThreeRetriesWithVisibleReason() = runBlocking {
        var attempts = 0
        try {
            retryAiModelResponse({}) { attempts++; response(listOf(AiToolCall("call", "read", "{"))) }
            fail()
        } catch (error: AiProviderException) { assertTrue(error.message!!.contains("已重试3次")) }
        assertEquals(4, attempts)
    }
    @Test fun nativeSearchFailureRetriesBeforeReturningFinalFailure() = runBlocking {
        var attempts = 0
        val answer = retryAiModelResponse({}) { attempts++; response(searchError = "搜索服务离线") }
        assertEquals(4, attempts)
        assertTrue(answer.searchError!!.contains("已重试3次"))
    }
}
