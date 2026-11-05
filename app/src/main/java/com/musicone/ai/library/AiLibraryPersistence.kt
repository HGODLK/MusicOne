package com.musicone

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/** 持久版本由触发器维护，用户在其他入口的操作也能使待执行计划失效。 */
internal fun SQLiteDatabase.ensureAiLibrarySchema() {
    execSQL("CREATE TABLE IF NOT EXISTS ai_track_field_versions(song_id TEXT NOT NULL,field TEXT NOT NULL,revision INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(song_id,field))")
    listOf("title_override", "artist_override", "album_override").forEach { field ->
        execSQL("CREATE TRIGGER IF NOT EXISTS ai_song_${field}_update AFTER UPDATE OF $field ON songs WHEN OLD.$field IS NOT NEW.$field BEGIN INSERT OR IGNORE INTO ai_track_field_versions(song_id,field) VALUES(NEW.id,'$field'); UPDATE ai_track_field_versions SET revision=revision+1 WHERE song_id=NEW.id AND field='$field'; END")
        execSQL("CREATE TRIGGER IF NOT EXISTS ai_song_${field}_delete AFTER DELETE ON songs BEGIN INSERT OR IGNORE INTO ai_track_field_versions(song_id,field) VALUES(OLD.id,'$field'); UPDATE ai_track_field_versions SET revision=revision+1 WHERE song_id=OLD.id AND field='$field'; END")
    }
    execSQL("CREATE TABLE IF NOT EXISTS ai_library_versions(id TEXT PRIMARY KEY, revision INTEGER NOT NULL DEFAULT 0, title_revision INTEGER NOT NULL DEFAULT 0)")
    execSQL("INSERT OR IGNORE INTO ai_library_versions(id) SELECT id FROM playlists")
    execSQL("CREATE TABLE IF NOT EXISTS ai_library_plans(id TEXT PRIMARY KEY, data TEXT NOT NULL)")
    execSQL("CREATE TABLE IF NOT EXISTS ai_library_results(id TEXT PRIMARY KEY, data TEXT NOT NULL)")
    execSQL("CREATE TABLE IF NOT EXISTS ai_library_result_tracks(handle TEXT NOT NULL,position INTEGER NOT NULL,data TEXT NOT NULL,PRIMARY KEY(handle,position))")
    execSQL("CREATE TABLE IF NOT EXISTS ai_library_member_versions(playlist_id TEXT NOT NULL,song_id TEXT NOT NULL,revision INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(playlist_id,song_id))")
    execSQL("CREATE TRIGGER IF NOT EXISTS ai_playlist_insert AFTER INSERT ON playlists BEGIN INSERT OR REPLACE INTO ai_library_versions(id,revision,title_revision) VALUES(NEW.id,COALESCE((SELECT revision+1 FROM ai_library_versions WHERE id=NEW.id),1),COALESCE((SELECT title_revision+1 FROM ai_library_versions WHERE id=NEW.id),1)); END")
    execSQL("CREATE TRIGGER IF NOT EXISTS ai_playlist_update AFTER UPDATE ON playlists BEGIN UPDATE ai_library_versions SET revision=revision+1,title_revision=title_revision+CASE WHEN OLD.title!=NEW.title THEN 1 ELSE 0 END WHERE id=NEW.id; END")
    execSQL("CREATE TRIGGER IF NOT EXISTS ai_playlist_delete AFTER DELETE ON playlists BEGIN UPDATE ai_library_versions SET revision=revision+1,title_revision=title_revision+1 WHERE id=OLD.id; END")
    listOf("INSERT" to "NEW", "UPDATE" to "NEW", "DELETE" to "OLD").forEach { (event, row) ->
        execSQL("CREATE TRIGGER IF NOT EXISTS ai_members_${event.lowercase()} AFTER $event ON members BEGIN UPDATE ai_library_versions SET revision=revision+1 WHERE id=$row.playlist_id; END")
        execSQL("CREATE TRIGGER IF NOT EXISTS ai_member_revision_${event.lowercase()} AFTER $event ON members BEGIN INSERT OR IGNORE INTO ai_library_member_versions(playlist_id,song_id) VALUES($row.playlist_id,$row.song_id); UPDATE ai_library_member_versions SET revision=revision+1 WHERE playlist_id=$row.playlist_id AND song_id=$row.song_id; END")
    }
}

internal fun SQLiteDatabase.aiVersion(id: String, titleOnly: Boolean = false): Long = rawQuery(
    "SELECT ${if (titleOnly) "title_revision" else "revision"} FROM ai_library_versions WHERE id=?", arrayOf(id),
).use { if (it.moveToFirst()) it.getLong(0) else -1 }

internal fun SQLiteDatabase.aiEditable(id: String): Boolean {
    if (id == PHONE_STORAGE_PLAYLIST_ID || id == ALL_LOCAL_PLAYLIST_ID) return false
    return rawQuery("SELECT source_id,remote_folder FROM playlists WHERE id=?", arrayOf(id)).use {
        it.moveToFirst() && it.isNull(0) && it.isNull(1)
    }
}

internal fun SQLiteDatabase.aiLoad(table: String, id: String): JSONObject? = rawQuery("SELECT data FROM $table WHERE id=?", arrayOf(id))
    .use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }

internal fun SQLiteDatabase.aiSave(table: String, id: String, data: JSONObject) {
    execSQL("INSERT OR REPLACE INTO $table(id,data) VALUES(?,?)", arrayOf(id, data.toString()))
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
internal fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

internal data class AiLibraryChange(
    val playlistId: String, val oldTitle: String, val newTitle: String?,
    val tracks: List<MusicTrack>, val duplicateCount: Int,
    val removedTracks: List<Pair<String, String>> = emptyList(),
)
internal data class AiLibraryPlan(val planId: String, val kind: String, val changes: List<AiLibraryChange>, val status: String,
    val details: List<String> = emptyList(), val groups: List<AiChangeGroupUi> = emptyList(), val summary: String? = null)

internal fun SQLiteDatabase.aiMemberVersion(playlistId: String, songId: String): Long = rawQuery(
    "SELECT revision FROM ai_library_member_versions WHERE playlist_id=? AND song_id=?", arrayOf(playlistId, songId),
).use { if (it.moveToFirst()) it.getLong(0) else 0 }

internal fun SQLiteDatabase.aiMemberExists(playlistId: String, songId: String): Boolean = rawQuery(
    "SELECT 1 FROM members WHERE playlist_id=? AND song_id=?", arrayOf(playlistId, songId),
).use { it.moveToFirst() }

internal fun SQLiteDatabase.aiTrackFieldVersion(id: String, field: String): Long = rawQuery(
    "SELECT revision FROM ai_track_field_versions WHERE song_id=? AND field=?", arrayOf(id, field),
).use { if (it.moveToFirst()) it.getLong(0) else 0 }
