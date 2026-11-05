package com.musicone

import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** 只在本机按行检索历史正文，查询不进入模型上下文，也不切换正在使用的会话数据库。 */
internal fun aiSearchConversations(folder: File, conversations: List<AiConversationSummary>, query: String,
    checkActive: () -> Unit = {}): List<AiConversationUi> {
    val needle = query.trim()
    return conversations.mapNotNull { conversation ->
        checkActive()
        if (needle.isBlank() || conversation.title.contains(needle, true))
            return@mapNotNull AiConversationUi(conversation.id, conversation.title, conversation.updatedAt)
        val path = if (conversation.id == "default") File(folder, "session.db") else File(folder, "conversations/${conversation.id}.db")
        if (!path.isFile) return@mapNotNull null
        var snippet: String? = null
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT data FROM ui_messages ORDER BY position DESC", null).use { rows ->
                while (rows.moveToNext() && snippet == null) {
                    checkActive()
                    val message = JSONObject(rows.getString(0))
                    val text = listOf(message.optString("text"), message.optString("result"),
                        message.optJSONObject("receipt")?.optString("summary").orEmpty()).joinToString("\n")
                    val index = text.indexOf(needle, ignoreCase = true)
                    if (index >= 0) snippet = text.substring(maxOf(0, index - 25), minOf(text.length, index + needle.length + 65))
                        .replace('\n', ' ')
                }
            }
        }
        snippet?.let { AiConversationUi(conversation.id, conversation.title, conversation.updatedAt, it) }
    }
}

internal class AiHistoryNavigation(private val scope: CoroutineScope, private val session: AiSessionStore,
    private val state: MutableStateFlow<AiAssistantUiState>) {
    private var queryJob: Job? = null
    fun open() { state.update { it.copy(historyVisible = true) }; search(state.value.historyQuery) }
    fun close() { queryJob?.cancel(); state.update { it.copy(historyVisible = false, historySearching = false) } }
    fun search(query: String) {
        queryJob?.cancel()
        state.update { it.copy(historyQuery = query, historySearching = true) }
        queryJob = scope.launch {
            try {
                if (query.isNotBlank()) delay(180)
                val results = withContext(Dispatchers.IO) {
                    val context = currentCoroutineContext()
                    session.searchConversations(query) { context.ensureActive() }
                }
                ensureActive()
                state.update { if (it.historyQuery == query) it.copy(historyResults = results, historySearching = false) else it }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.update { it.copy(historySearching = false, error = "无法搜索历史对话，请重试") } }
        }
    }
}
