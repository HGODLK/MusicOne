package com.musicone

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiContextTest {
    private val tools = JSONArray()

    @Test fun mimoGreetingWithFullProductionToolsDoesNotRequireCompressionOrRecovery() {
        for (id in listOf("mimo-v2.6-flash", "mimo-v2.6-pro", "mimo-v2.6-pro-ultraspeed")) {
            val config = AiConfiguration(AiProvider.OTHER, id)
            val schema = aiToolSchema(config)
            val capacity = AiProviderProtocol.contextTokens(id, null)!!
            val history = listOf(message("user", "MusicOne保存的音乐偏好资料：当前没有保存的音乐偏好"),
                message("user", "你好").put("musiconeTurnId", "greeting"))
            assertFalse(AiContextBudget.needsCompaction(capacity,
                AiContextBudget.size(AiContext.messages(AiProvider.OTHER, history, JSONObject(), capacity, schema), schema).toLong(), config.maxOutputTokens))
            val request = AiContext.messages(AiProvider.OTHER, history,
                JSONObject().put("latestUserRequest", "你好"), capacity, schema)
            assertEquals(history.size + 1, request.length())
            assertEquals(history.last().toString(), request.getJSONObject(request.length() - 1).toString())
            assertFalse(request.toString().contains("任务概览已截短"))
        }
    }

    @Test fun normalTurnsKeepStablePrefixAndAppendWithoutCheckpointInsertion() {
        val history = listOf(message("user", "点评我的歌单"), message("assistant", "请引用一个歌单"))
        val checkpoint = JSONObject().put("cursor", "50").put("task", "正在处理")
        val first = AiContext.messages(AiProvider.DEEPSEEK, history, checkpoint, null, tools)
        val appended = AiContext.messages(AiProvider.DEEPSEEK, history + message("user", "已引用"),
            JSONObject().put("cursor", "100"), null, tools)
        assertEquals(history.size + 1, first.length())
        assertEquals(first.length() + 1, appended.length())
        for (index in 0 until first.length()) assertEquals(first.getJSONObject(index).toString(), appended.getJSONObject(index).toString())
        assertEquals("已引用", appended.getJSONObject(appended.length() - 1).getString("content"))
        assertFalse(first.toString().contains("MusicOne任务记录"))
    }

    @Test fun compressedRoundsRetainCompleteAssistantAndToolPairs() {
        val old = listOf(message("user", "旧任务" + "甲".repeat(3000)), call("old"), response("old"))
        val latest = listOf(message("user", "当前目标"), calls("first", "second"), response("first"), response("second"), message("assistant", "这批已读取"))
        val result = AiContext.messages(AiProvider.DEEPSEEK, old + latest,
            JSONObject().put("goal", "当前目标"), capacityForBudget(1600), tools, forceCompact = true)
        assertFalse(result.toString().contains("旧任务"))
        assertTrue(result.toString().contains("当前目标"))
        assertToolPairs(result)
        assertTrue(result.toString().contains("first"))
        assertTrue(result.toString().contains("second"))
    }

    @Test fun oversizedSingleRoundRollsWholeToolGroupsAndRetainsCheckpoint() {
        val checkpoint = JSONObject().put("goal", "逐首处理全部歌单")
            .put("cursor", "cursor-500").put("processed", 500).put("total", 10000)
        val original = checkpoint.toString()
        val history = listOf(message("user", "逐首处理" + "乙".repeat(2000)),
            call("older"), response("older", "丙".repeat(2000)),
            calls("latest-a", "latest-b"), response("latest-a"), response("latest-b"))
        val result = AiContext.messages(AiProvider.DEEPSEEK, history, checkpoint, capacityForBudget(650), tools, forceCompact = true)
        assertFalse(result.toString().contains("older"))
        assertTrue(result.toString().contains("latest-a"))
        assertTrue(result.toString().contains("latest-b"))
        assertToolPairs(result)
        val checkpointMessage = result.getJSONObject(1)
        assertEquals("user", checkpointMessage.getString("role"))
        val retained = JSONObject(checkpointMessage.getString("content").substringAfter("："))
        assertEquals("cursor-500", retained.getString("cursor"))
        assertEquals(500, retained.getInt("processed"))
        assertEquals(10000, retained.getInt("total"))
        assertEquals(original, checkpoint.toString())
    }

    @Test fun estimateUsesUtf8BytesForChineseAndAscii() {
        assertEquals(3, AiContext.estimate("歌"))
        assertEquals(6, AiContext.estimate("歌abc"))
    }

    @Test fun oversizedSingleToolGroupBecomesBoundedRecoverableSummary() {
        val payload = JSONObject().put("handle", "real-result-handle").put("cursor", "50")
            .put("status", "ok").put("items", "巨量歌曲资料".repeat(10_000))
        val history = listOf(message("user", "读取全量"), call("big-call"), response("big-call", payload.toString()))
        val capacity = capacityForBudget(1700)
        val result = AiContext.messages(AiProvider.DEEPSEEK, history, JSONObject().put("goal", "读取全量"), capacity, tools, forceCompact = true)
        assertTrue(result.toString().contains("real-result-handle"))
        assertTrue(result.toString().contains("read_result"))
        assertTrue(result.toString().contains("read_task"))
        assertFalse(result.toString().contains("巨量歌曲资料"))
        assertFalse(result.toString().contains("tool_calls"))
        assertTrue(AiContextBudget.size(result, tools) <= capacity - aiDefaultOutputTokens(AiProvider.DEEPSEEK))
        assertToolPairs(result)
    }

    @Test fun oldToolResultsAndCurrentToolGroupStayComplete() {
        val oldPayload = JSONObject().put("handle", "old-handle").put("cursor", "old-2")
            .put("status", "ok").put("items", "旧歌曲资料".repeat(500))
        val currentPayload = JSONObject().put("handle", "current-handle").put("trackRefs", JSONArray().put("track-1"))
            .put("status", "ok").put("items", "当前歌曲资料".repeat(500))
        val history = listOf(message("user", "旧读取"), call("old-call"), response("old-call", oldPayload.toString()),
            message("user", "继续读取"), call("current-call"), response("current-call", currentPayload.toString()))
        val result = AiContext.messages(AiProvider.DEEPSEEK, history, JSONObject(), capacityForBudget(20_000), tools)
        assertTrue(result.toString().contains("old-handle"))
        assertTrue(result.toString().contains("旧歌曲资料"))
        assertTrue(result.toString().contains("current-handle"))
        assertTrue(result.toString().contains("当前歌曲资料"))
        val currentTool = result.getJSONObject(result.length() - 1)
        assertTrue(currentTool.getString("content").contains("\"trackRefs\":[\"track-1\"]"))
        assertTrue(history[2].toString().contains("旧歌曲资料"))
    }

    @Test fun hugeCheckpointDoesNotConsumeHistoryBudgetOrMutateOriginalTask() {
        val checkpoint = JSONObject().put("latestUserRequest", "处理所有歌单")
            .put("task", JSONObject().put("notes", "完整约束".repeat(10_000)))
            .put("cursor", "remote-page-90")
        val original = checkpoint.toString()
        val history = listOf(message("user", "处理所有歌单" + "详细原文".repeat(4000)), call("new"), response("new"))
        val capacity = capacityForBudget(1800)
        val result = AiContext.messages(AiProvider.DEEPSEEK, history, checkpoint, capacity, tools, forceCompact = true)
        assertTrue(result.toString().contains("read_task"))
        assertTrue(result.toString().contains("remote-page-90"))
        assertTrue(result.toString().contains("new"))
        assertTrue(AiContextBudget.size(result, tools) <= capacity - aiDefaultOutputTokens(AiProvider.DEEPSEEK))
        assertEquals(original, checkpoint.toString())
        assertToolPairs(result)
    }

    @Test fun tinyCapacityDoesNotSilentlyRaisePayloadBudget() {
        try {
            AiContext.messages(AiProvider.DEEPSEEK, listOf(message("user", "你好")), JSONObject(), 256, tools, forceCompact = true)
            fail("容量不足必须明确拒绝，不能抬高预算")
        } catch (error: AiProviderException) {
            assertEquals(AiFailureKind.CONTEXT_LIMIT, error.kind)
        }
    }

    @Test fun oldSearchResultsKeepFailuresCursorAndAllTrackPayload() {
        val search = JSONObject().put("sourceResults", JSONArray()
            .put(JSONObject().put("sourceId", "lx").put("source", "洛雪音源").put("status", "failed")
                .put("searchInvoked", true).put("message", "真实音源错误"))
            .put(JSONObject().put("sourceId", "qq").put("source", "QQ音乐").put("count", 50)
                .put("searchInvoked", true).put("nextCursor", "qq-next").put("handle", "qq-result")
                .put("items", "私有歌曲资料".repeat(600))))
        val history = listOf(message("user", "用洛雪搜，没结果再搜其他音源"), call("search"), response("search", search.toString()),
            message("user", "它返回什么"), call("latest"), response("latest"))
        val result = AiContext.messages(AiProvider.DEEPSEEK, history, JSONObject(), capacityForBudget(20000), tools)
        val receipt = JSONObject(result.objects().first { it.optString("tool_call_id") == "search" }.getString("content"))
        val sources = receipt.getJSONArray("sourceResults")
        assertEquals("lx", sources.getJSONObject(0).getString("sourceId"))
        assertEquals("failed", sources.getJSONObject(0).getString("status"))
        assertEquals("真实音源错误", sources.getJSONObject(0).getString("message"))
        assertTrue(sources.getJSONObject(0).getBoolean("searchInvoked"))
        assertEquals(50, sources.getJSONObject(1).getInt("count"))
        assertEquals("qq-next", sources.getJSONObject(1).getString("nextCursor"))
        assertTrue(receipt.toString().contains("私有歌曲资料"))
        assertTrue(history[2].toString().contains("私有歌曲资料"))
        assertToolPairs(result)
    }

    @Test fun searchFailureBeforeInvocationDoesNotBecomeAReportedSourceResponse() {
        val receipt = aiSearchReceipt(JSONObject().put("sourceId", "lx").put("searchInvoked", false)
            .put("status", "error").put("message", "未启用"))
        assertFalse(receipt.getBoolean("searchInvoked"))
        assertEquals("error", receipt.getString("status"))
        assertEquals("未启用", receipt.getString("message"))
    }

    @Test fun oldRemotePageReceiptRetainsEveryReferenceAndBothPaginationBoundaries() {
        val refs = (0 until 100).map { "real-result-handle:$it" }
        val payload = JSONObject().put("handle", "real-result-handle").put("count", 100).put("totalCount", 121)
            .put("complete", false).put("scope", "page").put("pageComplete", true).put("collectionComplete", false)
            .put("isLastPage", false).put("unknownTotalCount", false).put("cursor", JSONObject.NULL).put("nextCursor", "remote-100")
            .put("items", JSONArray(refs.map { JSONObject().put("trackRef", it).put("title", "冗长歌曲详情".repeat(20)) }))
        val history = listOf(message("user", "全部导入"), call("old-page"), response("old-page", payload.toString()),
            call("last-page"), response("last-page"))
        val request = AiContext.messages(AiProvider.OTHER, history, JSONObject(), 1_048_576, tools)
        val receipt = JSONObject(request.objects().single { it.optString("tool_call_id") == "old-page" }.getString("content"))
        assertEquals(refs, receipt.getJSONArray("items").objects().map { it.getString("trackRef") })
        assertEquals(121, receipt.getInt("totalCount"))
        assertTrue(receipt.isNull("cursor"))
        assertEquals("remote-100", receipt.getString("nextCursor"))
        assertTrue(receipt.getBoolean("pageComplete"))
        assertFalse(receipt.getBoolean("collectionComplete"))
        assertFalse(receipt.getBoolean("isLastPage"))
        assertFalse(receipt.getBoolean("unknownTotalCount"))
        assertEquals("page", receipt.getString("scope"))
        assertEquals(100, receipt.getJSONArray("items").length())
    }

    @Test fun oldExplicitTrackReferencesAreNeverSilentlyLimitedTo64() {
        val refs = (0 until 121).map { "actual-handle:$it" }
        val history = listOf(message("user", "保存全部"), call("chosen"),
            response("chosen", JSONObject().put("trackRefs", JSONArray(refs)).toString()), call("latest"), response("latest"))
        val request = AiContext.messages(AiProvider.OTHER, history, JSONObject(), 1_048_576, tools)
        val receipt = JSONObject(request.objects().single { it.optString("tool_call_id") == "chosen" }.getString("content"))
        assertEquals(refs, receipt.getJSONArray("trackRefs").strings())
    }

    @Test fun fourThousandTracksKeepFullHistoryAndStableCachePrefix() {
        val history = mutableListOf(message("user", "处理全部4000首歌"))
        var previous = AiContext.messages(AiProvider.OTHER, history, JSONObject(), 1_048_576, tools)
        repeat(80) { page ->
            val payload = JSONObject().put("handle", "page-$page").put("items", JSONArray((0 until 50).map { index ->
                JSONObject().put("id", "qq-${page * 50 + index}").put("title", "第${page * 50 + index}首歌曲完整标题")
                    .put("artists", "真实歌手").put("album", "真实专辑")
            }))
            history += call("page-$page")
            history += response("page-$page", payload.toString())
            val request = AiContext.messages(AiProvider.OTHER, history, JSONObject().put("page", page), 1_048_576, tools)
            for (i in 0 until previous.length()) assertEquals(previous.getJSONObject(i).toString(), request.getJSONObject(i).toString())
            previous = request
        }
        assertEquals(161, history.size)
        assertTrue(previous.toString().contains("第3999首歌曲完整标题"))
        assertFalse(AiContextBudget.needsCompaction(1_048_576, AiContextBudget.size(previous, tools).toLong(), aiDefaultOutputTokens(AiProvider.OTHER)))
        assertToolPairs(previous)
    }

    private fun capacityForBudget(budget: Int): Int {
        val prefix = message("system", aiSystemPrompt(AiProvider.DEEPSEEK))
        return budget + AiContextBudget.estimate(prefix.toString()) + AiContextBudget.estimate(tools.toString()) +
            64 + aiDefaultOutputTokens(AiProvider.DEEPSEEK)
    }

    private fun assertToolPairs(messages: JSONArray) {
        val pending = linkedSetOf<String>()
        for (index in 0 until messages.length()) {
            val message = messages.getJSONObject(index)
            if (message.optString("role") == "tool") {
                assertTrue("tool响应必须有前置调用", pending.remove(message.getString("tool_call_id")))
            } else {
                assertTrue("新消息前必须完成上一组工具响应", pending.isEmpty())
                val calls = message.optJSONArray("tool_calls") ?: continue
                for (callIndex in 0 until calls.length()) pending += calls.getJSONObject(callIndex).getString("id")
            }
        }
        assertTrue("工具调用不能缺失响应", pending.isEmpty())
    }

    private fun message(role: String, content: String) = JSONObject().put("role", role).put("content", content)
    private fun call(id: String) = calls(id)
    private fun calls(vararg ids: String) = message("assistant", "").put("tool_calls", JSONArray(ids.map {
        JSONObject().put("id", it).put("type", "function")
            .put("function", JSONObject().put("name", "read_result").put("arguments", "{}"))
    }))
    private fun response(id: String, content: String = "已读取") = message("tool", content).put("tool_call_id", id)
}
