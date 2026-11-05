package com.musicone

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File

/**
 * 歌词计划的确认与撤销。每项记录修改前后的 AI 歌词层原文（before / after），
 * 确认时当前状态必须仍等于 before；撤销时当前状态必须仍等于 after，否则跳过，保留用户后续修改。
 * 用到的 AI 歌词文件内容保存在计划的 files 里，文件被清理后撤销仍能写回。
 */
internal class AiLyricPlans(private val store: MusicLibraryStore) {
    fun validate(db: SQLiteDatabase, changes: List<JSONObject>) = changes.forEach { change ->
        require(AiLyricLayer.raw(db, change.getString("key")) == change.nullable("before")) { "《${change.optString("title")}》的歌词在确认前发生变化，请重新生成计划" }
        change.optJSONObject("lyricPath")?.let { path ->
            require(manualPath(db, change.getString("songId")) == path.nullable("before")) { "《${change.optString("title")}》的歌词关联已变化，请重新生成计划" }
        }
    }

    fun apply(db: SQLiteDatabase, changes: List<JSONObject>): List<JSONObject> {
        val written = mutableListOf<File>()
        try {
            return changes.map { change ->
                val after = change.nullable("after")
                after?.let { JSONObject(it).optJSONObject("file")?.optString("name") }?.let { name ->
                    val target = File(AiLyricLayer.directory(store), name)
                    if (!target.isFile) {
                        val content = change.optJSONObject("files")?.optString(name).orEmpty()
                        require(content.isNotBlank()) { "歌词文件内容缺失，请重新生成计划" }
                        target.parentFile?.mkdirs()
                        target.writeText(content)
                        written += target
                    }
                }
                AiLyricLayer.write(db, change.getString("key"), after)
                change.optJSONObject("lyricPath")?.let { path ->
                    // 只改关联，原歌词文件保留在原处。
                    db.update("songs", ContentValues().apply { put("lyric_path", path.nullable("after")) }, "id=?", arrayOf(change.getString("songId")))
                    store.noteLyricsChanged()
                }
                JSONObject(change.toString())
            }
        } catch (error: Exception) {
            // 事务回滚时一并删掉本次新写的文件，不留下无主文件。
            written.forEach(File::delete)
            throw error
        }
    }

    /** 生成反向计划；当前状态已被其他操作改动的项计入跳过。 */
    fun undo(db: SQLiteDatabase, actual: List<JSONObject>): Pair<List<JSONObject>, Int> {
        var skipped = 0
        val changes = actual.mapNotNull { change ->
            val path = change.optJSONObject("lyricPath")
            val layerMatches = AiLyricLayer.raw(db, change.getString("key")) == change.nullable("after")
            val pathMatches = path == null || manualPath(db, change.getString("songId")) == path.nullable("after")
            if (!layerMatches || !pathMatches) { skipped++; return@mapNotNull null }
            JSONObject(change.toString()).put("before", change.opt("after")).put("after", change.opt("before"))
                .put("label", "撤销：" + change.optString("label"))
                .put("beforeText", change.optString("afterText")).put("afterText", change.optString("beforeText"))
                .apply { path?.let { put("lyricPath", JSONObject().put("before", it.opt("after")).put("after", it.opt("before"))) } }
                .apply { remove("warning") }
        }
        return changes to skipped
    }

    private fun manualPath(db: SQLiteDatabase, songId: String): String? =
        db.rawQuery("SELECT lyric_path FROM songs WHERE id=?", arrayOf(songId)).use { if (it.moveToFirst()) it.getString(0) else null }
}

private fun JSONObject.nullable(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)

/** 确认窗口只说明改了哪首歌、改的是哪一类，不展示逐行差异。 */
internal fun aiLyricChangeGroups(data: JSONObject): List<AiChangeGroupUi> =
    (data.optJSONArray("lyricActual") ?: data.optJSONArray("lyricChanges"))?.objects().orEmpty().map { change ->
        val title = listOf(change.optString("title"), change.optString("artists")).filter(String::isNotBlank).joinToString(" · ")
        AiChangeGroupUi(title, listOf(AiFieldChangeUi(change.optString("label"), change.optString("beforeText"), change.optString("afterText"))))
    }

internal fun aiLyricChangeSummary(data: JSONObject, executed: Boolean): String? {
    val changes = (data.optJSONArray("lyricActual") ?: data.optJSONArray("lyricChanges"))?.objects().orEmpty()
    if (changes.isEmpty()) return null
    val songs = changes.map { it.optString("key") }.distinct().size
    val warnings = changes.mapNotNull { it.optional("warning") }.distinct()
    return "${if (executed) "已" else "将"}修改 $songs 首歌的歌词，原歌词文件不会被改动或删除。" + warnings.joinToString("") { "\n$it" }
}
