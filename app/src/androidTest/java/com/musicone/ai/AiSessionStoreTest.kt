package com.musicone

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 协议落盘增量、内存裁剪和重启恢复都使用独立文件数据库验证。 */
@RunWith(AndroidJUnit4::class)
class AiSessionStoreTest {
    @Test fun deletingHistoryPreservesOtherConversationsAndDoesNotResurrectLegacySession() = isolated { store, _ ->
        val original = store.currentConversationId
        store.writeSnapshot(sentHistory(), JSONObject(), listOf(AiChatMessageUi("old", "assistant", "旧回复")), false, false, null, null)
        val kept = store.newConversation("保留对话")
        store.writeSnapshot(sentHistory(), JSONObject(), listOf(AiChatMessageUi("kept", "assistant", "保留正文")), false, false, null, null)
        store.deleteConversations(setOf(original))
        assertEquals(kept, store.currentConversationId)
        assertFalse(store.listConversations().any { it.id == original })
        assertTrue(store.searchConversations("旧回复").isEmpty())
        assertEquals("保留正文", store.read().getJSONArray("messages").getJSONObject(0).getString("text"))
        val timestamp = store.listConversations().single().updatedAt
        store.writeSnapshot(sentHistory(), JSONObject().put("latestUserRequest", "仅更新断点"),
            listOf(AiChatMessageUi("kept", "assistant", "保留正文")), false, true, null, null)
        assertEquals(timestamp, store.listConversations().single().updatedAt)
    }

    @Test fun historySearchMatchesTitlesAndOtherConversationBodiesWithoutSelectingThem() = isolated { store, _ ->
        val older = store.newConversation("夜跑推荐记录")
        store.writeSnapshot(sentHistory(), JSONObject().put("marker", "older"), listOf(
            AiChatMessageUi("older-message", "assistant", "为你挑选了小提琴协奏曲，适合专注听歌。"),
        ), false, false, "older-plan", null)
        val current = store.newConversation("当前会话")
        store.writeSnapshot(sentHistory(), JSONObject().put("marker", "current"), listOf(
            AiChatMessageUi("current-message", "assistant", "当前正文只有爵士钢琴。"),
        ), false, false, "current-plan", null)
        val before = store.read().toString()

        val titleMatches = store.searchConversations("夜跑")
        assertEquals(listOf(older), titleMatches.map { it.id })
        assertEquals("夜跑推荐记录", titleMatches.single().title)
        val bodyMatches = store.searchConversations("小提琴协奏曲")
        assertEquals(listOf(older), bodyMatches.map { it.id })
        assertTrue(bodyMatches.single().snippet.contains("小提琴协奏曲"))
        assertEquals(listOf(current), store.searchConversations("爵士钢琴").map { it.id })
        assertTrue(store.searchConversations("从未出现的字符串").isEmpty())
        assertEquals(current, store.currentConversationId)
        assertEquals(before, store.read().toString())
        assertEquals("current-plan", store.read().getString("pendingPlan"))
    }

    @Test fun historySearchTreatsWildcardsAsLiteralTextAndSearchesSavedReceipts() = isolated { store, _ ->
        val literal = store.newConversation("字面符号记录")
        store.writeSnapshot(sentHistory(), JSONObject(), listOf(
            AiChatMessageUi("literal-message", "assistant", "音量 50%_mix，路径 C:\\music\\favorites。", result = "已添加节奏布鲁斯",
                receipt = AiReceiptUi("已完成字面回执汇总", listOf(AiChangeGroupUi("歌单", listOf(AiFieldChangeUi("名称", "旧", "新")))))),
        ), false, false, null, null)
        val plain = store.newConversation("普通记录")
        store.writeSnapshot(sentHistory(), JSONObject(), listOf(AiChatMessageUi("plain-message", "assistant", "普通内容完全没有通配符。")),
            false, false, null, null)

        listOf("%_", "50%_mix", "C:\\music\\favorites", "节奏布鲁斯", "字面回执汇总").forEach { query ->
            val matches = store.searchConversations(query)
            assertEquals(query, listOf(literal), matches.map { it.id })
            assertTrue(query, matches.single().snippet.contains(query))
        }
        assertTrue(store.searchConversations("50__mix").isEmpty())
        assertEquals(plain, store.currentConversationId)
        assertEquals("普通内容完全没有通配符。", store.read().getJSONArray("messages").getJSONObject(0).getString("text"))
    }

    @Test fun conversationsKeepIndependentPlansCheckpointsAndAutomaticTitles() = isolated { store, _ ->
        val original = store.currentConversationId
        val firstHistory = listOf(JSONObject().put("role", "user").put("content", "第一项任务"))
        store.writeSnapshot(firstHistory, JSONObject().put("latestUserRequest", "整理我的爵士歌单"), emptyList(), false, true, "plan-one", "operation-one")
        val other = store.newConversation()
        assertNotEquals(original, other)
        assertEquals(0, store.read().getJSONArray("history").length())
        store.writeSnapshot(listOf(JSONObject().put("role", "user").put("content", "第二项任务")),
            JSONObject().put("latestUserRequest", "为跑步推荐音乐"), emptyList(), false, false, "plan-two", null)
        store.selectConversation(original)
        val first = store.read()
        assertEquals("plan-one", first.getString("pendingPlan"))
        assertEquals("operation-one", first.getString("lastOperation"))
        assertEquals("第一项任务", first.getJSONArray("history").getJSONObject(0).getString("content"))
        store.writeSnapshot(first.getJSONArray("history").objects(), JSONObject().put("latestUserRequest", "后续请求不能覆盖标题"), emptyList(), false, false, null, null)
        assertEquals("新对话", store.listConversations().first { it.id == original }.title)
        store.selectConversation(other)
        assertEquals("plan-two", store.read().getString("pendingPlan"))
        assertEquals(2, store.listConversations().size)
    }

    @Test fun selectedConversationAndSharedPreferenceMemorySurviveNewStore() = isolated { store, folder ->
        val id = store.newConversation("自定标题")
        store.saveMemory("用户明确喜欢爵士音乐")
        save(store, sentHistory())
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = folder
        }
        val reloaded = AiSessionStore(context)
        assertEquals(id, reloaded.currentConversationId)
        assertEquals("用户明确喜欢爵士音乐", reloaded.readMemory())
        reloaded.newConversation()
        assertEquals("用户明确喜欢爵士音乐", reloaded.readMemory())
        assertEquals("自定标题", reloaded.listConversations().first { it.id == id }.title)
    }

    @Test fun trimmingMemoryRetainsDiskHistoryAndOnlyAppendsNewRows() = isolated { store, folder ->
        val all = (0 until 500).map { JSONObject().put("role", "user").put("content", "消息$it") }
        save(store, all)
        val full = store.read()
        assertEquals(500, full.getJSONArray("history").length())
        assertFalse(full.getBoolean("historyTruncated"))
        val tail = full.getJSONArray("history").objects().takeLast(128)
        save(store, tail + JSONObject().put("role", "user").put("content", "继续"))
        assertEquals(501, count(folder, "protocol"))
        val restored = store.read().getJSONArray("history")
        assertEquals(129, restored.length())
        assertEquals("继续", restored.getJSONObject(128).getString("content"))
        save(store, restored.objects())
        assertEquals(501, count(folder, "protocol"))
    }

    @Test fun compressedWindowOrderSurvivesReopeningWithoutReplayingArchivedRows() = isolated { store, folder ->
        val all = (0 until 200).map { JSONObject().put("role", "user").put("content", "原始消息$it") }
        save(store, all)
        val summary = JSONObject().put("role", "user").put("content", "压缩摘要")
        val active = listOf(summary) + all.takeLast(20)
        save(store, active)
        assertEquals(201, count(folder, "protocol"))
        val restored = store.read()
        assertEquals(active.map { it.toString() }, restored.getJSONArray("history").objects().map { it.toString() })
        assertFalse(restored.getBoolean("historyTruncated"))
        save(store, restored.getJSONArray("history").objects())
        assertEquals(201, count(folder, "protocol"))
    }

    @Test fun resumedWindowDropsOnlyOrphanToolsAndPreservesCheckpoint() = isolated { store, _ ->
        val history = mutableListOf<JSONObject>()
        repeat(150) { index -> history += JSONObject().put("role", if (index < 30) "tool" else "user").put("content", index.toString()) }
        val task = JSONObject().put("notes", "原始任务、限制和游标必须保留")
        store.writeSnapshot(history, task, emptyList(), true, true, "pending", "operation")
        val saved = store.read()
        assertEquals("user", saved.getJSONArray("history").getJSONObject(0).getString("role"))
        assertEquals(task.toString(), saved.getJSONObject("checkpoint").toString())
        assertEquals("pending", saved.getString("pendingPlan"))
        assertEquals("operation", saved.getString("lastOperation"))
        assertTrue(saved.getBoolean("historyTruncated"))
    }

    @Test fun uiUpsertAndExplicitResetKeepSessionBoundaries() = isolated { store, folder ->
        val history = listOf(JSONObject().put("role", "user").put("content", "问题"))
        val message = AiChatMessageUi("same-id", "assistant", "完整回答")
        store.writeSnapshot(history, JSONObject(), listOf(message), false, false, null, null)
        store.writeSnapshot(history, JSONObject(), listOf(message.copy(text = "更新回答")), false, false, null, null)
        assertEquals(1, count(folder, "protocol"))
        assertEquals(1, count(folder, "ui_messages"))
        assertEquals("更新回答", store.read().getJSONArray("messages").getJSONObject(0).getString("text"))
        store.reset()
        save(store, emptyList())
        assertEquals(0, count(folder, "protocol"))
        assertEquals(0, count(folder, "ui_messages"))
        assertEquals(0, store.read().getJSONArray("history").length())
    }

    @Test fun uiPagingReadsLatestFortyAndWalksAllEarlierRowsWithoutGapsOrDuplicates() = isolated { store, folder ->
        val all = (0 until 123).map { index -> AiChatMessageUi("paged-$index", "assistant", "回答$index") }
        store.writeSnapshot(sentHistory(), JSONObject(), all, false, false, null, null)
        val current = store.currentConversationId
        val latest = store.read()
        val tail = latest.getJSONArray("messages").objects().map { it.toAiMessage() }
        assertEquals(40, tail.size)
        assertEquals(all.takeLast(40).map { it.id }, tail.map { it.id })
        assertTrue(latest.getBoolean("hasEarlierMessages"))
        val complete = tail.toMutableList()
        var beforeId = tail.first().id
        var hasEarlier = true
        val pageSizes = mutableListOf<Int>()
        while (hasEarlier) {
            val page = store.readEarlierMessages(beforeId, current)
            assertTrue(page.messages.isNotEmpty())
            pageSizes += page.messages.size
            assertEquals(page.messages.map { it.id }.toSet(), page.positions.keys)
            complete.addAll(0, page.messages)
            beforeId = page.messages.first().id
            hasEarlier = page.hasEarlier
        }
        assertEquals(listOf(40, 40, 3), pageSizes)
        assertEquals(all.map { it.id }, complete.map { it.id })
        assertEquals(123, complete.map { it.id }.toSet().size)
        assertEquals(123, count(folder, "ui_messages"))
        val exhausted = store.readEarlierMessages(complete.first().id, current)
        assertTrue(exhausted.messages.isEmpty())
        assertFalse(exhausted.hasEarlier)
    }

    @Test fun savingOnlyRecentUiPagePreservesEarlierDiskRowsAndGlobalPositions() = isolated { store, folder ->
        val all = (0 until 123).map { index -> AiChatMessageUi("position-$index", "assistant", "回答$index") }
        store.writeSnapshot(sentHistory(), JSONObject(), all, false, false, null, null)
        val recent = store.read().getJSONArray("messages").objects().map { it.toAiMessage() }
        val changed = recent.filterNot { it.id == "position-105" }.map { message ->
            if (message.id == "position-100") message.copy(text = "更新后的回答") else message
        } + AiChatMessageUi("position-123", "assistant", "新回答")
        store.writeSnapshot(sentHistory(), JSONObject(), changed, false, false, null, null)
        assertEquals(123, count(folder, "ui_messages"))
        val expectedIds = (0..123).filterNot { it == 105 }.map { "position-$it" }
        val saved = SQLiteDatabase.openDatabase(File(folder, "ai/session.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT id,position,data FROM ui_messages ORDER BY position", null).use { rows ->
                buildList {
                    while (rows.moveToNext()) add(Triple(rows.getString(0), rows.getInt(1), JSONObject(rows.getString(2)).getString("text")))
                }
            }
        }
        assertEquals(expectedIds, saved.map { it.first })
        assertEquals((0..123).filterNot { it == 105 }, saved.map { it.second })
        assertEquals("更新后的回答", saved.single { it.first == "position-100" }.third)
        assertEquals("回答0", saved.first().third)
        assertEquals("新回答", saved.last().third)
        val reread = store.read()
        assertTrue(reread.getBoolean("hasEarlierMessages"))
        assertEquals(expectedIds.takeLast(40), reread.getJSONArray("messages").objects().map { it.getString("id") })
        // 再次保存相同尾页不能把磁盘早期历史重新编号或删掉。
        store.writeSnapshot(sentHistory(), JSONObject(), reread.getJSONArray("messages").objects().map { it.toAiMessage() }, false, false, null, null)
        assertEquals(123, count(folder, "ui_messages"))
    }

    @Test fun switchingConversationsRejectsPreviousPageAndLateSnapshot() = isolated { store, _ ->
        val first = store.currentConversationId
        store.writeSnapshot(sentHistory(), JSONObject(), (0 until 125).map { AiChatMessageUi("old-$it", "assistant", "旧会话$it") }, false, false, null, null)
        val oldBoundary = store.read().getJSONArray("messages").getJSONObject(0).getString("id")
        val second = store.newConversation()
        store.writeSnapshot(sentHistory(), JSONObject(), listOf(AiChatMessageUi("new-only", "assistant", "新会话")), false, false, null, null)
        try {
            store.readEarlierMessages(oldBoundary, first)
            fail("旧会话分页应被拒绝")
        } catch (_: IllegalArgumentException) { }
        store.writeSnapshot(sentHistory(), JSONObject().put("latestUserRequest", "迟到旧问题"),
            listOf(AiChatMessageUi("late-old", "assistant", "旧会话迟到快照")), false, false, "old-plan", null, first)
        assertEquals(second, store.currentConversationId)
        assertEquals(listOf("new-only"), store.read().getJSONArray("messages").objects().map { it.getString("id") })
        assertTrue(store.read().isNull("pendingPlan"))
        assertEquals(second to "夹具首问", store.titleSeed())
        store.selectConversation(first)
        assertEquals(40, store.read().getJSONArray("messages").length())
        assertEquals("old-45", store.readEarlierMessages(oldBoundary, first).messages.first().id)
    }

    @Test fun titleSeedRemembersFirstQuestionAndAiTitleWritesOnlyOnceToMatchingConversation() = isolated { store, _ ->
        val first = store.currentConversationId
        assertNull(store.titleSeed())
        val checkpoint = JSONObject().put("userRequests", org.json.JSONArray(listOf("第一条问题", "第二条问题")))
            .put("latestUserRequest", "第二条问题")
        store.writeSnapshot(sentHistory("第一条问题"), checkpoint, emptyList(), false, false, null, null)
        assertEquals(first to "第一条问题", store.titleSeed())
        store.writeSnapshot(sentHistory(), JSONObject().put("latestUserRequest", "第三条问题"), emptyList(), false, false, null, null)
        assertEquals(first to "第一条问题", store.titleSeed())
        assertFalse(store.saveAiTitle(first, "第三条问题", "不能覆盖首问"))
        assertFalse(store.saveAiTitle("不存在的会话", "第一条问题", "不能创建会话"))
        val second = store.newConversation()
        store.writeSnapshot(sentHistory("另一会话首问"), JSONObject().put("latestUserRequest", "另一会话首问"), emptyList(), false, false, null, null)
        assertEquals(second to "另一会话首问", store.titleSeed())
        assertFalse(store.saveAiTitle(second, "第一条问题", "不能串会话"))
        assertTrue(store.saveAiTitle(first, "第一条问题", "第一会话AI标题"))
        assertEquals(second, store.currentConversationId)
        assertEquals(second to "另一会话首问", store.titleSeed())
        assertEquals("新对话", store.listConversations().first { it.id == second }.title)
        assertEquals("第一会话AI标题", store.listConversations().first { it.id == first }.title)
        assertFalse(store.saveAiTitle(first, "第一条问题", "第二次不得覆盖"))
        assertTrue(store.saveAiTitle(second, "另一会话首问", "第二会话AI标题"))
        assertNull(store.titleSeed())
        store.selectConversation(first)
        assertNull(store.titleSeed())
        store.writeSnapshot(sentHistory(), JSONObject().put("latestUserRequest", "最新问题仍不能重置首问"), emptyList(), false, false, null, null)
        assertNull(store.titleSeed())
        assertFalse(store.saveAiTitle(first, "最新问题仍不能重置首问", "错误覆盖"))
        assertEquals("第一会话AI标题", store.listConversations().first { it.id == first }.title)
    }

    private fun sentHistory(text: String = "夹具首问", at: Long = 100L) = listOf(
        JSONObject().put("role", "user").put("content", text).put("musiconeTurnId", "fixture").put("musiconeSentAt", at),
    )

    @Test fun unsentConversationsCreateNeitherIndexEntriesNorDatabases() = isolated { store, folder ->
        assertTrue(store.listConversations().isEmpty())
        repeat(3) {
            store.newConversation()
            assertEquals(0, store.read().getJSONArray("messages").length())
            store.writeSnapshot(emptyList(), JSONObject().put("draft", "未发送的草稿"), emptyList(), false, false, null, null)
        }
        assertTrue(store.listConversations().isEmpty())
        assertFalse(File(folder, "ai/index.json").exists())
        assertTrue(folder.walkTopDown().none { it.extension == "db" })
    }

    @Test fun sentMessageSurvivesFailureAndOnlyNextUserSendChangesOrder() = isolated { store, _ ->
        val first = store.newConversation()
        val question = AiChatMessageUi("q", "user", "第一问。完整要求", sentAt = 100L)
        store.writeSnapshot(emptyList(), JSONObject(), listOf(question), true, false, null, null)
        assertEquals(first to question.text, store.titleSeed())
        assertEquals(100L, store.listConversations().single().updatedAt)
        val second = store.newConversation()
        store.writeSnapshot(emptyList(), JSONObject(), listOf(question.copy(id = "q2", sentAt = 200L)), false, true, null, null)
        store.selectConversation(first)
        store.writeSnapshot(emptyList(), JSONObject().put("task", "稍后完成"),
            listOf(question, AiChatMessageUi("a", "assistant", "稍后才生成的回答")), false, false, null, null)
        store.saveAiTitle(first, question.text, "模型概括标题")
        assertEquals(listOf(second, first), store.listConversations().map { it.id })
        store.writeSnapshot(emptyList(), JSONObject(), listOf(question, question.copy(id = "q3", sentAt = 300L)), false, false, null, null)
        assertEquals(listOf(first, second), store.listConversations().map { it.id })
        assertEquals("模型概括标题", store.listConversations().first().title)
    }

    @Test fun migrationRemovesOnlyEmptyRecordsAndKeepsRealFirstQuestion() = isolated { _, folder ->
        val ai = File(folder, "ai").apply { mkdirs() }
        fun oldDatabase(id: String, user: Boolean): File {
            val file = File(ai, "conversations/$id.db").apply { parentFile!!.mkdirs() }
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE protocol(position INTEGER PRIMARY KEY,data TEXT NOT NULL)")
                db.execSQL("CREATE TABLE ui_messages(id TEXT PRIMARY KEY,position INTEGER NOT NULL,data TEXT NOT NULL)")
                val message = AiChatMessageUi(id, if (user) "user" else "assistant", if (user) "真实首问。完整要求" else "欢迎", sentAt = if (user) 123L else 0L)
                db.execSQL("INSERT INTO ui_messages VALUES(?,?,?)", arrayOf<Any>(id, 0, message.toAiJson().toString()))
            }
            return file
        }
        val empty = oldDatabase("empty", false)
        val real = oldDatabase("real", true)
        val data = JSONObject().put("current", "empty").put("conversations", org.json.JSONArray().put(
            JSONObject().put("id", "empty").put("title", "新对话")).put(
            JSONObject().put("id", "real").put("title", "旧摘录").put("updatedAt", 999L)))
        assertTrue(aiMigrateConversationIndex(ai, data))
        assertFalse(empty.exists()); assertTrue(real.exists())
        val saved = data.getJSONArray("conversations").objects().single()
        assertEquals("real", saved.getString("id"))
        assertEquals("真实首问。完整要求", saved.getString("firstQuestion"))
        assertEquals(123L, saved.getLong("updatedAt"))
        assertEquals("新对话", saved.getString("title"))
        assertFalse(aiMigrateConversationIndex(ai, data))
    }

    private fun save(store: AiSessionStore, history: List<JSONObject>) =
        store.writeSnapshot(history, JSONObject(), emptyList(), false, false, null, null)
    private fun count(folder: File, table: String): Int = SQLiteDatabase.openDatabase(File(folder, "ai/session.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }
    }
    private fun isolated(block: (AiSessionStore, File) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(base.cacheDir, "ai-session-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = folder
        }
        val store = AiSessionStore(context)
        try { block(store, folder) } finally {
            val field = AiSessionStore::class.java.getDeclaredField("connection").apply { isAccessible = true }
            (field.get(store) as? SQLiteDatabase)?.close()
            folder.deleteRecursively()
        }
    }
}
