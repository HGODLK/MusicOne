package com.musicone

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import java.io.File
import java.util.IdentityHashMap
import org.json.JSONArray
import org.json.JSONObject

/** 协议历史和界面消息按行增量落库，任务断点和执行收据与本轮新增记录原子提交。 */
internal data class AiConversationSummary(val id: String, val title: String, val updatedAt: Long)

internal class AiSessionStore(context: Context) {
    private val folder = File(context.applicationContext.filesDir, "ai")
    private val memory = AiMusicMemoryStore.get(context)
    private var connection: SQLiteDatabase? = null
    private var historyRows = IdentityHashMap<JSONObject, Int>()
    private var nextHistoryPosition = 0
    private var messageMemo = emptyMap<String, AiChatMessageUi>()
    private var messagePositions = mutableMapOf<String, Int>()
    private var nextMessagePosition = 0
    private var metadataMemo: String? = null
    private var registry: JSONObject? = null
    private var temporaryTitle = "新对话"

    val currentConversationId: String
        @Synchronized get() = index().getString("current")

    /** 新建和切换都只更换当前会话，不删除其他会话的协议、记忆和执行收据。 */
    @Synchronized fun newConversation(title: String = "新对话"): String {
        val data = index()
        val id = java.util.UUID.randomUUID().toString()
        temporaryTitle = title.ifBlank { "新对话" }
        // 未发送的会话只保留内存身份，不创建索引记录或数据库。
        registry = JSONObject(data.toString()).put("current", id)
        discardConnection()
        return id
    }

    @Synchronized fun selectConversation(id: String) {
        val data = index()
        require(data.getJSONArray("conversations").objects().any { it.getString("id") == id }) { "对话不存在" }
        if (data.getString("current") == id) return
        saveIndex(JSONObject(data.toString()).put("current", id))
        discardConnection()
    }

    /** 当前会话必须先由控制器安全切走；删除不影响共享偏好及已确认的音乐资料。 */
    @Synchronized fun deleteConversations(ids: Set<String>) {
        require(currentConversationId !in ids) { "请先切换当前对话" }
        val data = JSONObject(index().toString())
        val entries = data.getJSONArray("conversations").objects()
        val removed = entries.filter { it.getString("id") in ids }
        data.put("conversations", JSONArray(entries.filterNot { it.getString("id") in ids }))
        saveIndex(data)
        removed.forEach { entry ->
            val id = entry.getString("id")
            val path = if (id == "default") File(folder, "session.db") else File(folder, "conversations/$id.db")
            SQLiteDatabase.deleteDatabase(path)
            if (id == "default") AtomicFile(File(folder, "session.json")).delete()
        }
    }

    @Synchronized fun listConversations(): List<AiConversationSummary> = index().getJSONArray("conversations").objects().map {
        AiConversationSummary(it.getString("id"), it.getString("title"), it.getLong("updatedAt"))
    }.sortedByDescending { it.updatedAt }

    fun searchConversations(query: String, checkActive: () -> Unit = {}): List<AiConversationUi> =
        aiSearchConversations(folder, listConversations(), query, checkActive)

    @Synchronized fun titleSeed(): Pair<String, String>? {
        val entry = index().getJSONArray("conversations").objects().firstOrNull { it.getString("id") == currentConversationId } ?: return null
        return entry.optString("firstQuestion").takeIf { it.isNotBlank() && !entry.optBoolean("aiTitleSet") }
            ?.let { entry.getString("id") to it }
    }

    @Synchronized fun saveAiTitle(id: String, firstQuestion: String, title: String): Boolean {
        val data = JSONObject(index().toString())
        val entry = data.getJSONArray("conversations").objects().firstOrNull { it.getString("id") == id } ?: return false
        if (entry.optBoolean("aiTitleSet") || entry.optString("firstQuestion") != firstQuestion) return false
        entry.put("title", title).put("titleSet", true).put("aiTitleSet", true)
        saveIndex(data)
        return true
    }

    /** 共享记忆只保存用户明确表达的音乐偏好，由调用者审核后写入。 */
    @Synchronized fun readMemory(): String = memory.snapshot().text

    @Synchronized fun saveMemory(value: String) = memory.save(value)

    /** 仅由后台线程调用；完整协议留在数据库，模型恢复窗口始终受限。 */
    @Synchronized fun read(): JSONObject {
        if (!hasSavedConversation()) return JSONObject().put("history", JSONArray()).put("messages", JSONArray())
        val db = database()
        migrateLegacyIfNeeded(db)
        val result = db.rawQuery("SELECT data FROM metadata WHERE id=1", null).use {
            if (it.moveToFirst()) JSONObject(it.getString(0)) else JSONObject()
        }
        val window = db.aiProtocolWindow(result)
        val tail = window.rows
        historyRows = IdentityHashMap<JSONObject, Int>().apply { tail.forEach { (index, message) -> put(message, index) } }
        result.put("history", JSONArray(tail.map { it.second }))
        result.put("historyTruncated", window.truncated)
        val page = db.aiMessagePage()
        val messages = JSONArray(page.messages.map { it.toAiJson() })
        result.put("hasEarlierMessages", page.hasEarlier)
        messagePositions = page.positions.toMutableMap()
        result.put("messages", messages)
        // 首次写入时使用值比较承接调用者的解码实例，之后复用不可变消息实例。
        messageMemo = page.messages.associateBy { it.id }
        metadataMemo = metadata(result).toString()
        return result
    }

    @Synchronized fun readEarlierMessages(beforeId: String, expectedConversationId: String): AiMessagePage {
        require(currentConversationId == expectedConversationId) { "对话已切换" }
        val db = database()
        val before = messagePositions[beforeId] ?: db.rawQuery("SELECT position FROM ui_messages WHERE id=?", arrayOf(beforeId)).use {
            require(it.moveToFirst()) { "历史消息不存在" }; it.getInt(0)
        }
        return db.aiMessagePage(before).also { messagePositions.putAll(it.positions) }
    }

    /** 兼容旧调用；常规保存应使用 writeSnapshot，避免先把全历史编码成一个 JSON。 */
    @Synchronized fun write(value: JSONObject) = writeSnapshot(
        value.optJSONArray("history")?.objects().orEmpty(), value.optJSONObject("checkpoint") ?: JSONObject(),
        value.optJSONArray("messages")?.objects().orEmpty().map { it.toAiMessage() },
        value.optBoolean("running"), value.optBoolean("canContinue"), value.optional("pendingPlan"), value.optional("lastOperation"),
    )

    /** 参数必须为调用前捕获的稳定快照；协议 JSONObject 加入历史后不得再原地修改。 */
    @Synchronized fun writeSnapshot(
        history: List<JSONObject>, checkpoint: JSONObject, messages: List<AiChatMessageUi>,
        running: Boolean, canContinue: Boolean, pendingPlan: String?, lastOperation: String?,
        expectedConversationId: String? = null,
    ) {
        if (expectedConversationId != null && currentConversationId != expectedConversationId) return
        val firstQuestion = aiFirstUserQuestion(messages, history)
        if (!hasSavedConversation() && firstQuestion == null) return
        val db = database()
        val nextRows = IdentityHashMap<JSONObject, Int>()
        var nextPosition = nextHistoryPosition
        val saved = JSONObject().put("checkpoint", checkpoint).put("running", running).put("canContinue", canContinue)
            .put("pendingPlan", pendingPlan).put("lastOperation", lastOperation)
        var encodedMetadata: String
        val oldMessages = messageMemo
        // 分页补回的旧消息已有数据库位置，不应被当作新回复重新置顶。
        val sentAt = aiLastUserSentAt(messages, history)
        val positions = messagePositions.toMutableMap()
        var nextUiPosition = nextMessagePosition
        db.beginTransaction()
        try {
            db.compileStatement("INSERT OR REPLACE INTO protocol(position,data) VALUES(?,?)").use { statement ->
                history.forEach { message ->
                    val row = historyRows[message]
                    if (row == null) {
                        val position = nextPosition++
                        statement.bindLong(1, position.toLong()); statement.bindString(2, message.toString())
                        statement.executeInsert()
                        nextRows[message] = position
                    } else nextRows[message] = row
                }
            }
            saved.put("protocolPositions", JSONArray(history.map { nextRows.getValue(it) }))
            encodedMetadata = saved.toString()
            val ids = messages.mapTo(hashSetOf()) { it.id }
            (oldMessages.keys - ids).forEach { id -> db.delete("ui_messages", "id=?", arrayOf(id)) }
            db.compileStatement("INSERT OR REPLACE INTO ui_messages(id,position,data) VALUES(?,?,?)").use { statement ->
                messages.forEach { message ->
                    val old = oldMessages[message.id]
                    if (old !== message && old != message) {
                        val position = positions.getOrPut(message.id) { nextUiPosition++ }
                        statement.bindString(1, message.id); statement.bindLong(2, position.toLong()); statement.bindString(3, message.toAiJson().toString())
                        statement.executeInsert()
                    }
                }
            }
            if (metadataMemo != encodedMetadata) {
                db.execSQL("INSERT OR REPLACE INTO metadata(id,data) VALUES(1,?)", arrayOf(encodedMetadata))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        // 事务成功后才推进缓存，失败重试仍会补齐所有未落库记录。
        historyRows = nextRows
        nextHistoryPosition = nextPosition
        messageMemo = messages.associateBy { it.id }
        messagePositions = positions
        nextMessagePosition = nextUiPosition
        metadataMemo = encodedMetadata
        updateConversation(firstQuestion, sentAt)
    }

    /** 仅清空当前会话的测试及维护入口；用户新建聊天应调用 newConversation。 */
    @Synchronized fun reset() {
        val db = database()
        db.beginTransaction()
        try {
            db.delete("protocol", null, null)
            db.delete("ui_messages", null, null)
            db.delete("metadata", null, null)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        historyRows.clear()
        nextHistoryPosition = 0
        messageMemo = emptyMap()
        messagePositions.clear(); nextMessagePosition = 0
        metadataMemo = null
        if (currentConversationId == "default") AtomicFile(File(folder, "session.json")).delete()
    }

    private fun database(): SQLiteDatabase = connection ?: run {
        ensureFolder()
        val id = currentConversationId
        val path = if (id == "default") File(folder, "session.db") else File(folder, "conversations/$id.db").also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).also { db ->
            db.enableWriteAheadLogging()
            db.execSQL("CREATE TABLE IF NOT EXISTS protocol(position INTEGER PRIMARY KEY,data TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS ui_messages(id TEXT PRIMARY KEY,position INTEGER NOT NULL,data TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS metadata(id INTEGER PRIMARY KEY,data TEXT NOT NULL)")
            nextHistoryPosition = db.rawQuery("SELECT COALESCE(MAX(position)+1,0) FROM protocol", null).use { it.moveToFirst(); it.getInt(0) }
            nextMessagePosition = db.rawQuery("SELECT COALESCE(MAX(position)+1,0) FROM ui_messages", null).use { it.moveToFirst(); it.getInt(0) }
            connection = db
        }
    }

    private fun ensureFolder() { check(folder.isDirectory || folder.mkdirs()) { "无法创建助手会话目录" } }

    private fun hasSavedConversation(): Boolean = index().getJSONArray("conversations").objects()
        .any { it.getString("id") == currentConversationId }

    private fun index(): JSONObject = registry ?: run {
        val file = File(folder, "index.json")
        val data = if (file.isFile || File(folder, "index.json.bak").isFile) {
            AtomicFile(file).openRead().bufferedReader().use { JSONObject(it.readText()) }
        } else {
            JSONObject().put("current", "default").put("conversations", JSONArray())
        }
        registry = data
        if (aiMigrateConversationIndex(folder, data)) saveIndex(data)
        data
    }

    private fun saveIndex(value: JSONObject) {
        ensureFolder()
        atomicWrite(AtomicFile(File(folder, "index.json")), value.toString())
        registry = value
    }

    private fun atomicWrite(file: AtomicFile, value: String) {
        val stream = file.startWrite()
        try { stream.write(value.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

    private fun discardConnection() {
        connection?.close()
        connection = null
        historyRows.clear()
        nextHistoryPosition = 0
        messageMemo = emptyMap()
        messagePositions.clear(); nextMessagePosition = 0
        metadataMemo = null
    }

    private fun updateConversation(firstQuestion: String?, sentAt: Long) {
        val data = JSONObject(index().toString())
        val entries = data.getJSONArray("conversations")
        val entry = entries.objects().firstOrNull { it.getString("id") == currentConversationId }
            ?: JSONObject().put("id", currentConversationId).put("title", temporaryTitle)
                .put("createdAt", sentAt).put("updatedAt", 0L).put("aiTitleSet", false)
                .put("userActivityVersion", 1).also { entries.put(it) }
        if (entry.optString("firstQuestion").isBlank() && firstQuestion != null) entry.put("firstQuestion", firstQuestion)
        entry.put("updatedAt", maxOf(entry.optLong("updatedAt"), sentAt))
        saveIndex(data)
    }

    private fun metadata(value: JSONObject): JSONObject = JSONObject().apply {
        listOf("checkpoint", "running", "canContinue", "pendingPlan", "lastOperation", "protocolPositions").forEach { key -> if (value.has(key)) put(key, value.get(key)) }
    }

    private fun migrateLegacyIfNeeded(db: SQLiteDatabase) {
        if (currentConversationId != "default") return
        val initialized = db.rawQuery("SELECT 1 FROM metadata WHERE id=1", null).use { it.moveToFirst() }
        if (initialized) return
        val legacy = File(folder, "session.json")
        if (!legacy.isFile && !File(folder, "session.json.bak").isFile) return
        val value = AtomicFile(legacy).openRead().bufferedReader().use { JSONObject(it.readText()) }
        // 保留旧文件直到全部记录在同一个事务中迁移成功。
        write(value)
        AtomicFile(legacy).delete()
    }
}

internal fun AiChatMessageUi.toAiJson(): JSONObject = JSONObject().put("id", id).put("role", role).put("text", text)
    .put("tracks", JSONArray(tracks.map { it.toStoredTrackJson() })).put("citations", JSONArray(citations))
    .put("result", result).put("usage", usage).put("reasoning", reasoning).put("turnId", turnId)
    .put("reasoningElapsedMs", reasoningElapsedMs).put("sentAt", sentAt)
    .put("progressSteps", JSONArray(progressSteps))
    .put("isNativeReceipt", isNativeReceipt)
    .put("playlist", playlist?.toAiPlaylistJson()).put("receipt", receipt?.toAiReceiptJson())

internal fun JSONObject.toAiMessage(): AiChatMessageUi = AiChatMessageUi(optString("id"), optString("role"), optString("text"),
    optJSONArray("tracks")?.let { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it)?.toStoredTrack() } }.orEmpty(),
    optJSONArray("citations")?.let { list -> (0 until list.length()).map { list.optString(it) } }.orEmpty(),
    optString("result").takeIf { it.isNotBlank() && it != "null" }, optString("usage").takeIf { it.isNotBlank() && it != "null" },
    sentAt = optLong("sentAt"), reasoning = optString("reasoning"), turnId = optString("turnId", ""),
    reasoningElapsedMs = if (isNull("reasoningElapsedMs")) null else optLong("reasoningElapsedMs"), isThinking = false,
    progressSteps = optJSONArray("progressSteps")?.let { values -> (0 until values.length()).map { values.optString(it) } }.orEmpty(),
    isNativeReceipt = optBoolean("isNativeReceipt") || optString("text") in setOf("操作已完成。", "已取消，歌单未修改。"),
    playlist = optJSONObject("playlist")?.toAiPlaylist(), receipt = optJSONObject("receipt")?.toAiReceipt())
