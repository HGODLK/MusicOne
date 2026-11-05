package com.musicone

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiChatRequestTest {
    private val history = JSONArray().put(JSONObject().put("role", "assistant").put("content", "")
        .put("reasoning_content", "实际返回的思考").put("tool_calls", JSONArray()))

    @Test fun quotasUseRequestedDefaultsAndCustomOutputIsNotClamped() {
        val deepSeek = AiConfiguration(modelId = "deepseek-flash")
        assertEquals(1_000_000, deepSeek.contextTokens)
        assertEquals(384_000, aiChatRequest(deepSeek, null, history, JSONArray()).getInt("max_tokens"))
        val other = AiConfiguration(AiProvider.OTHER, "custom")
        assertEquals(200_000, other.contextTokens)
        assertEquals(50_000, aiChatRequest(other, null, history, JSONArray()).getInt("max_tokens"))
        val custom = other.copy(contextTokens = 900_000, maxOutputTokens = 256_000)
        assertEquals(256_000, aiChatRequest(custom, null, history, JSONArray()).getInt("max_tokens"))
    }

    @Test fun otherAndDeepSeekPreserveTheSameToolContinuationAndOptions() {
        val messages = JSONArray("""[{"role":"assistant","content":"","reasoning_content":"已读取目录","annotations":[{"url":"https://example.org"}],"tool_calls":[{"id":"call-1","type":"function","function":{"name":"read_playlist","arguments":"{\"playlistId\":\"saved\"}"}}]},{"role":"tool","tool_call_id":"call-1","content":"{\"handle\":\"page-1\",\"continuation\":null}"}]""")
        val original = messages.toString()
        val tools = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "read_playlist")))
        val bodies = AiProvider.entries.map { provider -> aiChatRequest(AiConfiguration(provider, "same",
            thinkingEnabled = true, contextTokens = 900_000, maxOutputTokens = 250_000, reasoningEffort = "max"),
            null, messages, tools) }
        for (body in bodies) {
            assertEquals(original, body.getJSONArray("messages").toString())
            assertEquals("max", body.getString("reasoning_effort"))
            assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
            assertTrue(body.getJSONObject("stream_options").getBoolean("include_usage"))
            assertEquals(250_000, body.getInt("max_tokens"))
            assertEquals(tools.toString(), body.getJSONArray("tools").toString())
        }
        assertEquals(original, messages.toString())
    }

    @Test fun otherCustomEffortIsSentWithoutSubstitutionAndInvalidCapacityIsRejected() {
        val custom = AiConfiguration(AiProvider.OTHER, "custom", thinkingEnabled = true, reasoningEffort = "ultra")
        assertEquals("ultra", aiChatRequest(custom, null, history, JSONArray()).getString("reasoning_effort"))
        assertTrue(runCatching { aiChatRequest(custom.copy(maxOutputTokens = 200_000), null, history, JSONArray()) }.isFailure)
        assertNotNull(aiRequestOptionsError("2147483648", "50000", false, "low"))
        assertNotNull(aiRequestOptionsError("200000", "0", false, "low"))
        assertNotNull(aiRequestOptionsError("200000", "50000", true, ""))
    }

    @Test fun normalConfigurationRequiresSearchConfigurationAndDisablesThinking() {
        assertFalse(AiConfiguration().webSearchEnabled)
        assertFalse(AiConfiguration().thinkingEnabled)
        for (provider in AiProvider.entries) {
            val body = aiChatRequest(AiConfiguration(provider, "test"),
                AiModel("test", supportsThinking = true, supportsLowThinking = true), history, JSONArray())
            assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
            assertFalse(body.has("reasoning_effort"))
        }
    }

    @Test fun deepSeekUsesConfiguredEffortAndPreservesProtocolReasoning() {
        val body = aiChatRequest(AiConfiguration(modelId = "deepseek-flash", thinkingEnabled = true),
            AiModel("deepseek-flash", supportsThinking = true, supportsLowThinking = true), history, JSONArray())
        assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
        assertEquals("low", body.getString("reasoning_effort"))
        assertEquals(history.toString(), body.getJSONArray("messages").toString())
    }

    @Test fun explicitThinkingDoesNotDependOnIncompleteModelMetadata() {
        for (model in listOf(null, AiModel("future", supportsThinking = true))) {
            val config = AiConfiguration(modelId = "future", thinkingEnabled = true, reasoningEffort = "max")
            val body = aiChatRequest(config, model, history, JSONArray())
            assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
            assertEquals("max", body.getString("reasoning_effort"))
        }
    }

    @Test fun otherUsesOnlyStandardFieldsAndPreservesFunctionProtocol() {
        val tool = JSONObject().put("type", "function").put("function", JSONObject().put("name", "list_playlists"))
        val body = aiChatRequest(AiConfiguration(AiProvider.OTHER, "custom-model", webSearchEnabled = true, thinkingEnabled = true),
            AiModel("custom-model", supportsThinking = true), history, JSONArray().put(tool))
        assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
        assertEquals("low", body.getString("reasoning_effort"))
        assertFalse(body.has("max_completion_tokens"))
        assertTrue(body.getJSONObject("stream_options").getBoolean("include_usage"))
        assertEquals(50000, body.getInt("max_tokens"))
        assertEquals("function", body.getJSONArray("tools").getJSONObject(0).getString("type"))
        assertEquals(1, body.getJSONArray("tools").length())
        assertEquals("实际返回的思考", body.getJSONArray("messages").getJSONObject(0).getString("reasoning_content"))
        assertTrue(history.getJSONObject(0).has("reasoning_content"))
    }

    @Test fun summaryDisablesThinkingAndWebEvenForUnknownModel() {
        for (provider in AiProvider.entries) {
            val body = aiChatRequest(AiConfiguration(provider, "unknown", webSearchEnabled = false, thinkingEnabled = false),
                null, history, JSONArray())
            assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
            assertFalse(body.has("reasoning_effort"))
            assertFalse(body.has("tools"))
        }
    }

    @Test fun bothChatProvidersUseOnlyDeepSeekFunctionsWhenSearchIsConfigured() {
        for (provider in AiProvider.entries) {
            fun names(enabled: Boolean) = aiToolSchema(AiConfiguration(provider, "model", webSearchEnabled = enabled))
                .objects().map { it.getJSONObject("function").getString("name") }
            assertTrue(names(true).containsAll(listOf("web_search", "web_fetch")))
            assertFalse(names(false).contains("web_search"))
            assertFalse(names(false).contains("web_fetch"))
            assertTrue(names(false).contains("search_tracks"))
            assertEquals(names(false), names(true).filterNot { it in setOf("web_search", "web_fetch") })
        }
    }

    @Test fun requestStripsLocalTurnMarkerButKeepsToolProtocolAndOriginalHistory() {
        val messages = JSONArray("""[{"role":"user","content":"读目录","musiconeTurnId":"turn-1"},{"role":"assistant","content":"","reasoning_content":"已返回的思考","tool_calls":[{"id":"call-1","type":"function","function":{"name":"list_playlists","arguments":"{}"}}]},{"role":"tool","tool_call_id":"call-1","content":"[]"}]""")
        val original = messages.toString()
        val body = aiChatRequest(AiConfiguration(modelId = "test", thinkingEnabled = false), null, messages, JSONArray())
        val protocol = body.getJSONArray("messages")
        assertFalse(protocol.getJSONObject(0).has("musiconeTurnId"))
        assertEquals(messages.getJSONObject(1).toString(), protocol.getJSONObject(1).toString())
        assertEquals(messages.getJSONObject(2).toString(), protocol.getJSONObject(2).toString())
        assertEquals(original, messages.toString())
    }
}
