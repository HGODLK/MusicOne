package com.musicone

import android.database.sqlite.SQLiteDatabase

internal data class AiMessagePage(val messages: List<AiChatMessageUi>, val positions: Map<String, Int>, val hasEarlier: Boolean)

/** 界面分页独立于模型协议窗口，只解码当前页，不删除磁盘上的早期历史。 */
internal fun SQLiteDatabase.aiMessagePage(before: Int? = null, limit: Int = 40): AiMessagePage {
    val size = limit.coerceIn(1, 100)
    val rows = mutableListOf<Pair<Int, AiChatMessageUi>>()
    val where = if (before == null) "" else "WHERE position<?"
    val args = if (before == null) arrayOf((size + 1).toString()) else arrayOf(before.toString(), (size + 1).toString())
    rawQuery("SELECT position,data FROM ui_messages $where ORDER BY position DESC LIMIT ?", args).use { cursor ->
        while (cursor.moveToNext()) rows += cursor.getInt(0) to org.json.JSONObject(cursor.getString(1)).toAiMessage()
    }
    val page = rows.take(size).asReversed()
    return AiMessagePage(page.map { it.second }, page.associate { it.second.id to it.first }, rows.size > size)
}
