package com.musicone

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

internal data class AiProtocolWindow(val rows: List<Pair<Int, JSONObject>>, val truncated: Boolean)

/** 归档历史和当前上下文窗口分开存，恢复时按记录的行号还原，不能硬截成固定条数。 */
internal fun SQLiteDatabase.aiProtocolWindow(metadata: JSONObject): AiProtocolWindow {
    val positions = metadata.optJSONArray("protocolPositions")
    val rows = mutableListOf<Pair<Int, JSONObject>>()
    if (positions != null) {
        val order = (0 until positions.length()).map(positions::getInt)
        val records = hashMapOf<Int, JSONObject>()
        order.distinct().chunked(500).forEach { batch ->
            rawQuery("SELECT position,data FROM protocol WHERE position IN (${batch.joinToString(",") { "?" }})",
                batch.map(Int::toString).toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) records[cursor.getInt(0)] = JSONObject(cursor.getString(1))
            }
        }
        order.forEach { index -> records[index]?.let { rows += index to it } }
        val missing = rows.size != order.size
        val count = rows.size
        while (rows.firstOrNull()?.second?.optString("role") == "tool") rows.removeAt(0)
        return AiProtocolWindow(rows, missing || rows.size != count)
    }
    // 兼容旧版没存行号的数据，只取最近 128 条，避免把全部历史和摘要混在一起发给模型。
    rawQuery("SELECT position,data FROM protocol ORDER BY position DESC LIMIT 128", null).use { cursor ->
        while (cursor.moveToNext()) rows += cursor.getInt(0) to JSONObject(cursor.getString(1))
    }
    rows.reverse()
    while (rows.firstOrNull()?.second?.optString("role") == "tool") rows.removeAt(0)
    return AiProtocolWindow(rows, rows.firstOrNull()?.first?.let { it > 0 } ?: true)
}
