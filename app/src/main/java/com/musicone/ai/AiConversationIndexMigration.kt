package com.musicone

import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一次性按真实消息迁移旧索引；读取失败时保留原记录，避免误删历史。 */
internal fun aiMigrateConversationIndex(folder: File, data: JSONObject): Boolean {
    val entries = data.getJSONArray("conversations").objects().toMutableList()
    val defaultDb = File(folder, "session.db")
    val legacy = File(folder, "session.json")
    if (entries.none { it.optString("id") == "default" } && (defaultDb.isFile || legacy.isFile || File(folder, "session.json.bak").isFile))
        entries += JSONObject().put("id", "default").put("title", "新对话").put("updatedAt", 0L)
    var changed = false
    val retained = entries.filter { entry ->
        if (entry.optInt("userActivityVersion") == 1) return@filter true
        val id = entry.getString("id")
        val path = if (id == "default") defaultDb else File(folder, "conversations/$id.db")
        val evidence = runCatching {
            val messages = mutableListOf<AiChatMessageUi>()
            val history = mutableListOf<JSONObject>()
            if (path.isFile) SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT data FROM ui_messages ORDER BY position", null).use { rows ->
                    while (rows.moveToNext()) { val row = JSONObject(rows.getString(0)); if (row.optString("role") == "user") messages += row.toAiMessage() }
                }
                db.rawQuery("SELECT data FROM protocol ORDER BY position", null).use { rows ->
                    while (rows.moveToNext()) { val row = JSONObject(rows.getString(0)); if (row.optString("role") == "user") history += row }
                }
            }
            if (id == "default" && (legacy.isFile || File(folder, "session.json.bak").isFile)) {
                val old = AtomicFile(legacy).openRead().bufferedReader().use { JSONObject(it.readText()) }
                messages += old.optJSONArray("messages")?.objects()?.map { it.toAiMessage() }.orEmpty()
                history += old.optJSONArray("history")?.objects().orEmpty()
            }
            aiFirstUserQuestion(messages, history) to aiLastUserSentAt(messages, history)
        }.getOrElse { return@filter true }
        changed = true
        if (evidence.first == null) {
            if (path.isFile) SQLiteDatabase.deleteDatabase(path)
            if (id == "default") AtomicFile(legacy).delete()
            false
        } else {
            entry.put("firstQuestion", evidence.first).put("updatedAt", evidence.second).put("userActivityVersion", 1)
            if (!entry.optBoolean("aiTitleSet")) entry.put("title", "新对话").put("titleSet", false)
            true
        }
    }
    if (changed) data.put("conversations", JSONArray(retained))
    return changed
}
