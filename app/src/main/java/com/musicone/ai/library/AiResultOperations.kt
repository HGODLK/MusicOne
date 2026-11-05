package com.musicone

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/** 以歌曲真实身份做集合运算，不根据同名、艺人或模型判断合并不同版本。 */
internal fun aiTrackSet(operation: String, left: List<MusicTrack>, right: List<MusicTrack>): List<MusicTrack> {
    fun MusicTrack.key() = source.id to id
    val rightIds = right.map { it.key() }.toHashSet()
    return when (operation) {
        "intersection" -> left.filter { it.key() in rightIds }
        "difference" -> left.filterNot { it.key() in rightIds }
        "union" -> left + right
        else -> throw AiToolInputFailure("invalid_operation", "集合运算只支持intersection、difference或union")
    }.distinctBy { it.key() }
}

/** 远端集合必须有从首游标到末游标的连续证据，不能只凭总数或最后一页宣称完整。 */
internal fun aiRequireCompleteResults(metadata: List<JSONObject>) {
    if (metadata.isEmpty()) throw AiToolInputFailure("incomplete_collection", "请提供至少一个实际读取的结果句柄")
    metadata.filterNot { it.optBoolean("complete") }.groupBy { it.optJSONObject("pagination")?.optString("collectionKey").orEmpty() }
        .forEach { (key, group) ->
            if (key.isBlank()) throw AiToolInputFailure("incomplete_collection", "此结果没有完整集合或分页连续性证据，请重新读取完整集合")
            val pages = group.map { it.getJSONObject("pagination") }.associateBy { it.optString("inputCursor") }
            if (pages.size != group.size) throw AiToolInputFailure("incomplete_collection", "混入了重复首游标的分页，请只保留一条读取链")
            var cursor = ""
            val seen = hashSetOf<String>()
            while (true) {
                if (!seen.add(cursor)) throw AiToolInputFailure("incomplete_collection", "远端集合存在重复分页，不能进行完整集合比较")
                val page = pages[cursor] ?: throw AiToolInputFailure("incomplete_collection", "远端集合缺少首批或中间分页，不能用残缺数据比较",
                    group.first().getJSONObject("pagination").let { p -> aiContinuation("remote_tracks", JSONObject().put("playlistRef", p.getString("playlistRef"))
                        .apply { if (cursor.isNotEmpty()) put("cursor", cursor) }) })
                val next = page.optional("nextCursor") ?: break
                cursor = next
            }
            if (seen.size != pages.size) throw AiToolInputFailure("incomplete_collection", "混入了不同读取批次的分页，请使用一条连续分页链")
        }
}

internal class AiResultOperations(private val db: SQLiteDatabase) {
    fun tracks(handles: List<String>, requireComplete: Boolean = false): List<MusicTrack> {
        val metadata = handles.distinct().map { handle ->
            db.aiLoad("ai_library_results", handle) ?: throw AiToolInputFailure("invalid_handle", "结果句柄不存在，请重新读取歌单")
        }
        if (requireComplete) aiRequireCompleteResults(metadata)
        return handles.distinct().zip(metadata).flatMap { (handle, data) ->
            buildList {
                db.rawQuery("SELECT data FROM ai_library_result_tracks WHERE handle=? ORDER BY position", arrayOf(handle)).use { rows ->
                    while (rows.moveToNext()) add(JSONObject(rows.getString(0)).toStoredTrack() ?: error("歌曲资料无效"))
                }
            }.also { if (it.size != data.getInt("count")) throw AiToolInputFailure("incomplete_collection", "本机结果曲目不完整，请重新读取") }
        }
    }
}
