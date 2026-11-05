package com.musicone

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiConversationPresentationTest {
    @Test fun reasoningOnlyMessagesRemainVisibleDuringStreamingAndAfterRestore() {
        val reasoning = AiChatMessageUi("old", "assistant", "", reasoning = "旧思考", isThinking = true)
        assertEquals(listOf(reasoning), aiVisibleMessages(listOf(reasoning)))
        val restored = reasoning.toAiJson().toAiMessage()
        assertEquals(listOf(restored), aiVisibleMessages(listOf(restored)))
        assertFalse(restored.isThinking)
        assertEquals(1, aiVisibleMessages(listOf(reasoning.copy(reasoning = ""))).size)
        assertTrue(aiVisibleMessages(listOf(reasoning.copy(reasoning = "", isThinking = false))).isEmpty())
        assertEquals(1, aiVisibleMessages(listOf(reasoning.copy(progressSteps = listOf("已读取歌单")))).size)
    }
    @Test fun legacyEmptyUsageMessagesAreRemovedAndUserTurnsRemainSeparate() {
        val visible = aiVisibleMessages(listOf(
            AiChatMessageUi("u", "user", "找歌", turnId = ""),
            AiChatMessageUi("a", "assistant", "第一段", turnId = ""),
            AiChatMessageUi("empty", "assistant", "", usage = "旧用量", turnId = ""),
            AiChatMessageUi("b", "assistant", "第二段", turnId = ""),
            AiChatMessageUi("u2", "user", "下一题", turnId = ""),
            AiChatMessageUi("c", "assistant", "另一轮", turnId = ""),
        ))
        assertEquals(4, visible.size)
        assertEquals("第一段\n\n第二段", visible[1].text)
        assertEquals("另一轮", visible.last().text)
    }

    @Test fun reasoningStreamAndLegacyMergeKeepOriginalContentInIncrementalProjection() {
        val cache = AiVisibleMessagesCache()
        val first = AiChatMessageUi("a", "assistant", "", turnId = "", reasoning = "读取目录", isThinking = true)
        assertEquals(listOf(first), cache.update(listOf(first)))
        val updated = first.copy(reasoning = "读取目录，然后检查歌曲")
        assertEquals(listOf(updated), cache.update(listOf(updated)))
        val answer = AiChatMessageUi("b", "assistant", "这是结果", turnId = "", reasoning = "核对完成")
        val merged = cache.update(listOf(updated.copy(isThinking = false), answer)).single()
        assertEquals("读取目录，然后检查歌曲\n\n核对完成", merged.reasoning)
        assertEquals("这是结果", merged.text)
        assertFalse(merged.isThinking)
    }

    @Test fun incrementalPresentationMatchesLegacyMergeAcrossStreamUpdatesAndPrepending() {
        val cache = AiVisibleMessagesCache()
        val user = AiChatMessageUi("u", "user", "问题", turnId = "")
        val first = AiChatMessageUi("a", "assistant", "第一段", turnId = "")
        val empty = AiChatMessageUi("e", "assistant", "", turnId = "")
        val second = AiChatMessageUi("b", "assistant", "第", turnId = "")
        val original = listOf(user, first, empty, second)
        assertEquals(aiVisibleMessages(original), cache.update(original))
        val streaming = original.dropLast(1) + second.copy(text = "第二段")
        assertEquals(aiVisibleMessages(streaming), cache.update(streaming))
        val prepended = listOf(AiChatMessageUi("older", "user", "历史问题")) + streaming
        assertEquals(aiVisibleMessages(prepended), cache.update(prepended))
        val appended = prepended + AiChatMessageUi("next", "user", "下一题")
        assertEquals(aiVisibleMessages(appended), cache.update(appended))
        assertEquals(emptyList<AiChatMessageUi>(), cache.update(emptyList()))
    }

    @Test fun reasoningAndProgressSurvivePersistenceWithoutRestartingSpinner() {
        val message = AiChatMessageUi("turn", "assistant", "回答", reasoning = "模型实际返回的思考", turnId = "turn",
            reasoningElapsedMs = 2200, isThinking = true, progressSteps = listOf("已读取第二页"))
        val restored = message.toAiJson().toAiMessage()
        assertEquals(message.reasoning, restored.reasoning)
        assertEquals(message.progressSteps, restored.progressSteps)
        assertEquals(2200L, restored.reasoningElapsedMs)
        assertFalse(restored.isThinking)
    }

    @Test fun metadataReceiptKeepsRealDifferenceAndStableIdentityAcrossRestore() {
        val record = JSONObject().put("operationId", "operation").put("changes", JSONArray()).put("trackChanges", JSONArray().put(
            JSONObject().put("title", "旧歌名").put("fields", JSONArray().put(JSONObject()
                .put("field", "title").put("oldDisplay", "旧歌名").put("new", "新歌名")))))
        val first = aiNativeReceipt(record, true)
        assertEquals("native-operation", first.id)
        assertTrue(first.isNativeReceipt)
        assertTrue(first.result!!.contains("旧歌名 → 新歌名"))
        assertTrue(first.toAiJson().toAiMessage().isNativeReceipt)
        assertEquals(first, aiNativeReceipt(record, true))
    }

    @Test fun compactMemoryDeduplicatesAndBoundsUnicodeWithoutBreakingCharacters() {
        assertEquals("喜欢爵士\n喜欢民谣", aiCompactMemory(" 喜欢爵士\n喜欢爵士\n喜欢民谣 "))
        val bounded = aiCompactMemory("🎵喜欢爵士".repeat(500))
        assertTrue(bounded.toByteArray(Charsets.UTF_8).size <= 2000)
        assertFalse(bounded.contains('\uFFFD'))
        assertEquals(bounded, String(bounded.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }
}
