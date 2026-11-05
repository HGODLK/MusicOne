package com.musicone

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiProviderProtocolTest {
    @Test fun mimoModelsWithoutWindowAndOldCacheUseConfirmedCapacity() {
        val ids = listOf("mimo-v2.6-flash", "mimo-v2.6-pro", "mimo-v2.6-pro-ultraspeed")
        val data = org.json.JSONArray(ids.map { JSONObject().put("id", it) })
        val models = AiProviderProtocol.models(JSONObject().put("data", data), ids.first()).models
        assertTrue(models.all { it.contextTokens == 1_048_576 })
        ids.forEach {
            assertEquals(1_048_576, AiProviderProtocol.contextTokens(it, 0))
            assertEquals(1_048_576, AiProviderProtocol.contextTokens(it, null))
        }
        assertEquals(262144, AiProviderProtocol.contextTokens(ids.first(), 262144))
        assertNull(AiProviderProtocol.contextTokens("unknown", null))
    }

    @Test fun modelRefreshPreservesSelectionAndExcludesAudio() {
        val response = JSONObject("""{"data":[{"id":"mimo-new-pro"},{"id":"mimo-v9-asr"},{"id":"mimo-v9-tts-voiceclone"},{"id":"future-text","context_window":262144}]}""")
        val result = AiProviderProtocol.models(response, "future-text")
        assertEquals(listOf("mimo-new-pro", "future-text"), result.models.map { it.id })
        assertEquals("future-text", result.selectedModelId)
        assertNull(result.models.first().contextTokens)
        assertEquals(262144, result.models.last().contextTokens)
    }

    @Test fun removedSelectionRequiresExplicitNewSelection() {
        assertEquals("", AiProviderProtocol.models(JSONObject("""{"data":[{"id":"new"}]}"""), "old").selectedModelId)
        assertEquals("new", AiProviderProtocol.models(JSONObject("""{"data":[{"id":"new"}]}"""), "").selectedModelId)
    }

    @Test fun authenticationTimeoutRateAndBalanceAreDistinct() {
        assertEquals(AiFailureKind.AUTHENTICATION, AiProviderProtocol.failure(401, null).kind)
        assertEquals(AiFailureKind.PERMISSION, AiProviderProtocol.failure(403, null).kind)
        assertEquals(AiFailureKind.TIMEOUT, AiProviderProtocol.failure(504, null).kind)
        assertEquals(AiFailureKind.RATE_LIMIT, AiProviderProtocol.failure(429, null).kind)
        assertEquals(AiFailureKind.BALANCE, AiProviderProtocol.failure(402, null).kind)
        assertEquals(AiFailureKind.BALANCE, AiProviderProtocol.failure(429, JSONObject("""{"error":{"code":"insufficient_quota"}}""")).kind)
    }

    @Test fun streamAssemblesParallelToolsAndPreservesReasoningAndUsage() {
        val accumulator = AiStreamAccumulator()
        accumulator.accept(JSONObject("""{"choices":[{"delta":{"reasoning_content":"思考","tool_calls":[{"index":1,"id":"b","function":{"name":"second","arguments":"{"}},{"index":0,"id":"a","function":{"name":"first","arguments":"{}"}}]}}]}"""))
        accumulator.accept(JSONObject("""{"choices":[{"delta":{"content":"答","tool_calls":[{"index":1,"function":{"arguments":"}"}}],"annotations":[{"url":"https://example.org"}],"error_message":"搜索暂时不可用"},"finish_reason":"tool_calls"}]}"""))
        accumulator.accept(JSONObject("""{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105,"prompt_tokens_details":{"cached_tokens":80}}}"""))
        val result = accumulator.response()
        assertEquals("答", result.text)
        assertEquals("思考", result.assistantMessage.getString("reasoning_content"))
        assertEquals(listOf("a", "b"), result.toolCalls.map { it.id })
        assertEquals("{}", result.toolCalls.last().arguments)
        assertEquals(80L, result.usage.cachedTokens)
        assertEquals(1, result.annotations.length())
        assertEquals("搜索暂时不可用", result.searchError)
    }

    @Test fun deepSeekCacheUsageIsParsed() {
        assertEquals(42L, AiProviderProtocol.usage(JSONObject("""{"prompt_cache_hit_tokens":42}""")).cachedTokens)
    }

    @Test fun mimoNonStreamResponseRetainsWebCitationsAndToolReasoning() {
        val accumulator = AiStreamAccumulator()
        accumulator.accept(JSONObject("""{"choices":[{"message":{"role":"assistant","content":"结果","reasoning_content":"内部推理","annotations":[{"type":"url_citation","url":"https://example.org","title":"来源"}],"error_message":"plugin unavailable","tool_calls":[{"id":"m1","type":"function","function":{"name":"read","arguments":"{}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":12,"completion_tokens":8,"total_tokens":20,"prompt_tokens_details":{"cached_tokens":5},"completion_tokens_details":{"reasoning_tokens":3},"web_search_usage":{"tool_usage":2,"page_usage":4}}}"""))
        val response = accumulator.response()
        assertEquals("结果", response.text)
        assertEquals("内部推理", response.assistantMessage.getString("reasoning_content"))
        assertEquals("read", response.toolCalls.single().name)
        assertEquals(5L, response.usage.cachedTokens)
        assertEquals(3L, response.usage.reasoningTokens)
        assertEquals(2L, response.usage.searchCalls)
        assertEquals(4L, response.usage.searchPages)
        assertEquals("plugin unavailable", response.searchError)
    }

    @Test fun modelThinkingCapabilityDoesNotAssumeUnknownModelSupport() {
        val result = AiProviderProtocol.models(JSONObject("""{"data":[{"id":"mimo-v2.6-pro"},{"id":"unknown-next-model"},{"id":"future-with-effort","effort":{"supported_levels":["high"]}}]}"""), "mimo-v2.6-pro")
        assertTrue(result.models.first().supportsThinking)
        assertFalse(result.models[1].supportsThinking)
        assertTrue(result.models.last().supportsThinking)
    }

    @Test fun reasoningIsPublishedVerbatimAndPreservedInProtocolHistory() {
        val accumulator = AiStreamAccumulator()
        val delta = accumulator.accept(JSONObject("""{"choices":[{"delta":{"content":"可见文本","reasoning_content":"内部推理"}}]}"""))
        assertEquals("可见文本", delta.text)
        assertEquals("内部推理", delta.reasoning)
        assertEquals("内部推理", accumulator.response().assistantMessage.getString("reasoning_content"))
    }

    @Test fun supportedEffortMetadataOverridesKnownModelDefault() {
        val result = AiProviderProtocol.models(JSONObject("""{"data":[{"id":"deepseek-flash"},{"id":"deepseek-v4-pro","effort":{"supported_levels":["high"]}},{"id":"future","effort":{"supported_levels":["low","high"]}}]}"""), "")
        assertTrue(result.models[0].supportsLowThinking)
        assertFalse(result.models[1].supportsLowThinking)
        assertTrue(result.models[2].supportsLowThinking)
    }

    @Test fun reasoningOnlyResponseFinishesWithoutFabricatingText() {
        val accumulator = AiStreamAccumulator()
        val delta = accumulator.accept(JSONObject("""{"choices":[{"delta":{"reasoning_content":"只有实际思考"}}]}"""))
        assertEquals("只有实际思考", delta.reasoning)
        assertEquals("", delta.text)
        val finished = accumulator.accept(JSONObject("""{"choices":[{"delta":{},"finish_reason":"stop"}]}"""))
        assertEquals(AiChatDelta(), finished)
        assertEquals("stop", accumulator.response().finishReason)
        assertEquals("", accumulator.response().text)
        assertEquals("只有实际思考", accumulator.response().assistantMessage.getString("reasoning_content"))
    }

    @Test fun toolOnlyFinishRetainsReasoningAndCompleteFunctionArguments() {
        val accumulator = AiStreamAccumulator()
        accumulator.accept(JSONObject("""{"choices":[{"delta":{"reasoning_content":"读取目录","tool_calls":[{"index":0,"id":"read-1","type":"function","function":{"name":"list_","arguments":"{"}}]}}]}"""))
        accumulator.accept(JSONObject("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"playlists","arguments":"}"}}]},"finish_reason":"tool_calls"}]}"""))
        val result = accumulator.response()
        assertEquals("tool_calls", result.finishReason)
        assertEquals("读取目录", result.assistantMessage.getString("reasoning_content"))
        assertEquals("", result.text)
        assertEquals(AiToolCall("read-1", "list_playlists", "{}"), result.toolCalls.single())
        assertEquals("read-1", result.assistantMessage.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
    }
}
