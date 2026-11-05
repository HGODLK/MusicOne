package com.musicone

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 歌单生命周期只操作资料库关系，绝不修改歌曲记录或实际文件。 */
internal class AiPlaylistLifecycle {
    fun create(db: SQLiteDatabase, title: String, tracks: List<MusicTrack>): JSONObject {
        require(title.isNotBlank()) { "歌单名称不能为空" }
        val unique = tracks.distinctBy { it.id }
        return JSONObject().put("action", "create").put("playlistId", "library-${UUID.randomUUID()}")
            .put("oldTitle", title.trim()).put("version", -1).put("tracks", JSONArray(unique.map { it.toStoredTrackJson() }))
            .put("duplicateCount", tracks.size - unique.size)
            .put("creationSongs", JSONArray(unique.map { track -> JSONObject().put("id", track.id)
                .put("row", row(db, "songs", "id=?", arrayOf(track.id)) ?: JSONObject.NULL) }))
    }

    fun delete(db: SQLiteDatabase, id: String): JSONObject {
        require(db.aiDeletable(id)) { "只能删除 MusicOne 普通本地自建歌单，内置、派生和订阅歌单不能删除" }
        return JSONObject().put("action", "delete").put("playlistId", id).put("oldTitle", row(db, "playlists", "id=?", arrayOf(id))!!.getString("title"))
            .put("version", db.aiVersion(id)).put("playlistSnapshot", row(db, "playlists", "id=?", arrayOf(id)))
            .put("memberSnapshot", rows(db, "SELECT * FROM members WHERE playlist_id=? ORDER BY position,song_id", arrayOf(id)))
            .put("songSnapshot", rows(db, "SELECT songs.* FROM songs JOIN members ON members.song_id=songs.id WHERE members.playlist_id=? ORDER BY songs.id", arrayOf(id)))
    }

    fun undo(db: SQLiteDatabase, actual: JSONObject): JSONObject = when (actual.getString("action")) {
        "create" -> {
            val id = actual.getString("playlistId")
            require(db.aiDeletable(id) && db.aiVersion(id) == actual.getLong("finalVersion")) { "新建歌单已有后续修改，不能安全撤销" }
            delete(db, id)
        }
        "delete" -> {
            val id = actual.getString("playlistId")
            require(row(db, "playlists", "id=?", arrayOf(id)) == null && db.aiVersion(id) == actual.getLong("finalVersion")) { "原歌单身份已有后续变化，不能安全恢复" }
            JSONObject(actual.toString()).put("action", "restore").put("version", actual.getLong("finalVersion"))
        }
        else -> error("此歌单操作不能撤销")
    }

    fun validate(db: SQLiteDatabase, c: JSONObject) {
        val id = c.getString("playlistId")
        when (c.getString("action")) {
            "create" -> {
                require(row(db, "playlists", "id=?", arrayOf(id)) == null && db.aiVersion(id) == -1L) { "新歌单身份已发生冲突" }
                c.getJSONArray("creationSongs").objects().forEach { saved ->
                    val current = row(db, "songs", "id=?", arrayOf(saved.getString("id")))
                    require(if (saved.isNull("row")) current == null else current.toString() == saved.getJSONObject("row").toString()) { "歌曲资料在确认前发生变化" }
                }
            }
            "delete" -> {
                require(db.aiDeletable(id) && db.aiVersion(id) == c.getLong("version")) { "歌单权限或内容已改变，请重新生成计划" }
                require(row(db, "playlists", "id=?", arrayOf(id)).toString() == c.getJSONObject("playlistSnapshot").toString()) { "歌单资料已改变" }
                require(rows(db, "SELECT * FROM members WHERE playlist_id=? ORDER BY position,song_id", arrayOf(id)).toString() == c.getJSONArray("memberSnapshot").toString()) { "歌单成员已改变" }
                require(rows(db, "SELECT songs.* FROM songs JOIN members ON members.song_id=songs.id WHERE members.playlist_id=? ORDER BY songs.id", arrayOf(id)).toString() == c.getJSONArray("songSnapshot").toString()) { "歌曲资料在确认前发生变化" }
            }
            "restore" -> {
                require(row(db, "playlists", "id=?", arrayOf(id)) == null && db.aiVersion(id) == c.getLong("version")) { "原歌单身份已有后续变化，不能安全恢复" }
                require(aiOrdinaryPlaylist(c.getJSONObject("playlistSnapshot"))) { "原歌单没有恢复权限" }
                c.getJSONArray("songSnapshot").objects().forEach { song ->
                    require(row(db, "songs", "id=?", arrayOf(song.getString("id"))).toString() == song.toString()) { "原歌曲已有后续变化，不能安全恢复整批成员" }
                }
            }
        }
    }

    fun applyBeforeMembers(db: SQLiteDatabase, c: JSONObject) {
        if (c.getString("action") == "create") db.insertOrThrow("playlists", null, ContentValues().apply {
            put("id", c.getString("playlistId")); put("title", c.getString("oldTitle")); put("position", System.currentTimeMillis())
        })
    }

    fun apply(db: SQLiteDatabase, c: JSONObject): JSONObject {
        val id = c.getString("playlistId")
        when (c.getString("action")) {
            "delete" -> {
                db.delete("members", "playlist_id=?", arrayOf(id))
                require(db.delete("playlists", "id=?", arrayOf(id)) == 1) { "歌单已不存在" }
            }
            "restore" -> {
                db.insertOrThrow("playlists", null, values(c.getJSONObject("playlistSnapshot")))
                c.getJSONArray("memberSnapshot").objects().forEach { db.insertOrThrow("members", null, values(it)) }
            }
        }
        return JSONObject(c.toString()).put("finalVersion", db.aiVersion(id))
    }

    private fun row(db: SQLiteDatabase, table: String, where: String, args: Array<String>): JSONObject? =
        rows(db, "SELECT * FROM $table WHERE $where", args).objects().firstOrNull()
    private fun rows(db: SQLiteDatabase, sql: String, args: Array<String>): JSONArray = db.rawQuery(sql, args).use { cursor ->
        JSONArray().apply {
            while (cursor.moveToNext()) put(JSONObject().apply {
                cursor.columnNames.forEachIndexed { i, name -> put(name, when (cursor.getType(i)) {
                    android.database.Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                    android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                    android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                    else -> cursor.getString(i)
                }) }
            })
        }
    }
    private fun values(row: JSONObject) = ContentValues().apply {
        row.keys().forEach { key -> when (val value = row.get(key)) {
            JSONObject.NULL -> putNull(key)
            is Number -> put(key, value.toLong())
            else -> put(key, value.toString())
        } }
    }
}

/** 类型依据持久身份和订阅字段，标题不会授予或剥夺删除权限。 */
internal fun aiOrdinaryPlaylist(row: JSONObject): Boolean = row.optString("id") !in setOf(UNIFIED_PLAYLIST_ID, PHONE_STORAGE_PLAYLIST_ID, ALL_LOCAL_PLAYLIST_ID) &&
    listOf("source_id", "source_account", "source_collection", "remote_folder", "owner").all { row.isNull(it) } && row.optLong("source_revision") == 0L

internal fun SQLiteDatabase.aiDeletable(id: String): Boolean = rawQuery("SELECT * FROM playlists WHERE id=?", arrayOf(id)).use { cursor ->
    if (!cursor.moveToFirst()) false else aiOrdinaryPlaylist(JSONObject().apply {
        cursor.columnNames.forEachIndexed { i, name -> put(name, if (cursor.isNull(i)) JSONObject.NULL else cursor.getString(i)) }
    })
}
