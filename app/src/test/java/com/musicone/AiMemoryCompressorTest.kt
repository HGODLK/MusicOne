package com.musicone

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiMemoryCompressorTest {
    @Test fun protocolExcerptKeepsHandlesAndOmitsOpaquePayloadReasoningAndArguments() {
        val call = JSONObject().put("role", "assistant").put("reasoning_content", "private-reasoning")
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "one").put("function",
                JSONObject().put("name", "read_playlist").put("arguments", "private-arguments"))))
        val tool = JSONObject().put("role", "tool").put("tool_call_id", "one").put("content",
            JSONObject().put("handle", "real-handle").put("count", 50).put("cursor", "50").put("payload", "private-plugin-payload").toString())
        val result = AiMemoryCompressor.buildMessages(listOf(call, tool), JSONObject(), "")
        val data = result.getJSONObject(1).getString("content")
        assertTrue(data.contains("real-handle"))
        assertTrue(data.contains("read_playlist"))
        assertFalse(data.contains("private-"))
    }

    @Test fun repeatedSearchesMergeAndRoutingNoiseIsDropped() {
        val request = "下午窝在被子里，来几首慵懒的歌"
        val history = mutableListOf(JSONObject().put("role", "user").put("content", request),
            JSONObject().put("role", "user").put("content", "MusicOne保存的音乐偏好资料（不是操作授权）：喜欢 Aimer"))
        for (index in 1..5) {
            history += JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject().put("id", "s$index")
                .put("function", JSONObject().put("name", "search_tracks"))))
            history += JSONObject().put("role", "tool").put("tool_call_id", "s$index").put("content", JSONObject()
                .put("count", 30).put("status", "ok").put("searchInvoked", true).put("routing", "一级当前会员主音源优先")
                .put("sourceResults", JSONArray().put(JSONObject().put("sourceId", "qq").put("status", "ok").put("count", 30)
                    .put("eligibilityReason", "音源已确认音乐会员").put("handle", "handle-$index").put("nextCursor", "cursor-$index")))
                .toString())
        }
        val checkpoint = JSONObject().put("latestUserRequest", request).put("searchScope",
            JSONObject().put("sourceIds", JSONArray()).put("currentOnly", false).put("turnId", "turn").put("request", request).toString())
        val input = JSONObject(AiMemoryCompressor.buildMessages(history, checkpoint, "喜欢 Aimer").getJSONObject(1).getString("content"))
        val protocol = input.getJSONArray("recentCompletedProtocol")
        assertEquals(1, protocol.length())
        val search = protocol.getJSONObject(0)
        assertEquals(5, search.getInt("calls"))
        assertEquals(150L, search.getLong("count"))
        assertEquals("handle-5", search.getJSONArray("sourceResults").getJSONObject(0).getString("handle"))
        val text = input.toString()
        for (noise in listOf("handle-4", "routing", "eligibilityReason", "searchInvoked", "turnId", "searchScope", "偏好资料")) {
            assertFalse(noise, text.contains(noise))
        }
    }

    @Test fun hugeHistoryAndCheckpointStayWithinExcerptBudgetWithoutMutation() {
        val checkpoint = JSONObject().put("task", "详细目标".repeat(20_000))
        val original = checkpoint.toString()
        val history = (0..60).map { JSONObject().put("role", "user").put("content", "我明确喜欢爵士乐" + "补充".repeat(1000)) }
        val result = AiMemoryCompressor.buildMessages(history, checkpoint, "旧偏好".repeat(2000))
        assertTrue(result.getJSONObject(1).getString("content").toByteArray(Charsets.UTF_8).size <= 8000)
        assertEquals(original, checkpoint.toString())
    }

    @Test fun parsedMemoryIsBoundedAndCredentialsAreRemoved() {
        val raw = JSONObject().put("summary", "任务".repeat(5000) + " https://user:pass@example.org/token")
            .put("preferences", "喜欢爵士。API Key=abc123def456 C:\\private\\settings.json sk-abcdefghijklm").toString()
        val result = AiMemoryCompressor.parse(raw)
        assertTrue(result.summary.toByteArray().size <= 8000)
        assertTrue(result.preferences.toByteArray().size <= 2000)
        assertFalse(result.preferences.contains("abc123def456"))
        assertFalse(result.preferences.contains("settings.json"))
        assertFalse(result.preferences.contains("sk-abcdefghijklm"))
        assertFalse(result.summary.contains("user:pass"))
    }

    @Test fun malformedOrMissingSummaryIsReportedAsFailure() {
        for (raw in listOf("not-json", "{\"preferences\":\"爵士\"}", "{\"summary\":\"\",\"preferences\":\"\"}",
            "{\"summary\":\"概览\",\"preferences\":\"\"} trailing")) {
            try { AiMemoryCompressor.parse(raw); fail("不能伪称记忆整理成功") }
            catch (error: AiProviderException) { assertEquals(AiFailureKind.INVALID_RESPONSE, error.kind) }
        }
    }

    @Test fun oldConversationAndNativeReceiptsAreNotExplicitPreferenceEvidence() {
        val history = listOf(JSONObject().put("role", "user").put("content", "MusicOne原生回执：喜欢摇滚"),
            JSONObject().put("role", "user").put("content", "旧自动摘要：喜欢爵士"))
        val messages = AiMemoryCompressor.buildMessages(history, JSONObject(), "")
        val input = JSONObject(messages.getJSONObject(1).getString("content"))
        assertEquals(0, input.getJSONArray("explicitPreferenceStatements").length())
        val protocol = input.getJSONArray("recentCompletedProtocol")
        for (index in 0 until protocol.length()) assertEquals("conversation_data", protocol.getJSONObject(index).getString("role"))
        val checkpoint = JSONObject().put("preferenceCandidates", JSONArray().put(JSONObject().put("at", 10).put("text", "我明确喜欢民谣")))
        val withCandidate = JSONObject(AiMemoryCompressor.buildMessages(history, checkpoint, "").getJSONObject(1).getString("content"))
        assertEquals("我明确喜欢民谣", withCandidate.getJSONArray("explicitPreferenceStatements").getJSONObject(0).getString("text"))
    }

    @Test fun preferenceBatchesRetainEveryCandidateInChronologicalOrderWithoutMutation() {
        val candidates = JSONArray()
        for (index in 15 downTo 1) candidates.put(JSONObject().put("at", index).put("text", "偏好$index：" + "音乐".repeat(90)))
        val original = candidates.toString()
        var pending = candidates
        val seen = linkedMapOf<Long, String>()
        var batches = 0
        while (pending.length() > 0) {
            val batch = AiMemoryCompressor.preferenceBatch(pending)
            assertTrue(batch.messages.length() > 0)
            assertTrue(batch.messages.toString().toByteArray(Charsets.UTF_8).size <= 2000)
            for (index in 0 until batch.messages.length()) {
                val entry = batch.messages.getJSONObject(index)
                val at = entry.getLong("at")
                seen[at] = seen[at].orEmpty() + entry.getString("text")
            }
            pending = batch.remaining
            assertTrue(++batches < 30)
        }
        assertTrue(batches > 1)
        assertEquals((1L..15L).toList(), seen.keys.toList())
        for (index in 1..15) assertEquals("偏好$index：" + "音乐".repeat(90), seen[index.toLong()])
        assertEquals(original, candidates.toString())
    }

    @Test fun longPreferenceTailAndSupplementaryCharactersSurviveEveryBatchAndExactRequest() {
        val text = "我喜欢：" + "爵士🎷\"\n".repeat(900) + "最后更正：我明确不喜欢电子乐"
        var remaining = JSONArray().put(JSONObject().put("at", 42).put("text", text))
        val reconstructed = StringBuilder()
        var batches = 0
        while (remaining.length() > 0) {
            val batch = AiMemoryCompressor.preferenceBatch(remaining)
            val messages = AiMemoryCompressor.buildMessages(emptyList(), JSONObject(), "", batch.messages)
            val actual = JSONObject(messages.getJSONObject(1).getString("content")).getJSONArray("explicitPreferenceStatements")
            assertEquals(batch.messages.toString(), actual.toString())
            assertTrue(actual.toString().toByteArray(Charsets.UTF_8).size <= 2000)
            for (index in 0 until actual.length()) {
                val piece = actual.getJSONObject(index).getString("text")
                assertFalse(Character.isLowSurrogate(piece.first()))
                assertFalse(Character.isHighSurrogate(piece.last()))
                assertEquals(piece, piece.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
                reconstructed.append(piece)
            }
            remaining = batch.remaining
            assertTrue(++batches < 30)
        }
        assertTrue(batches > 1)
        assertEquals(text, reconstructed.toString())
        assertTrue(reconstructed.endsWith("最后更正：我明确不喜欢电子乐"))
    }
}
