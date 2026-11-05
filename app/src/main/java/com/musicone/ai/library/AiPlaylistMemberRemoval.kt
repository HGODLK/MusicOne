package com.musicone

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/** 移除和恢复只改歌单成员关系，版本墓碑阻止覆盖用户后续操作。 */
internal fun SQLiteDatabase.aiRemovalMembers(id: String, tracks: List<MusicTrack>): List<JSONObject> = tracks.distinctBy { it.id }.map { track ->
    rawQuery("SELECT members.position,members.added_at,songs.data FROM members JOIN songs ON songs.id=members.song_id WHERE members.playlist_id=? AND members.song_id=?",
        arrayOf(id, track.id)).use { row ->
        if (!row.moveToFirst()) throw AiToolInputFailure("not_a_member", "所选歌曲已不在目标歌单，请重新读取")
        val saved = JSONObject(row.getString(2)).toStoredTrack() ?: error("成员歌曲资料无效")
        require(saved.source.id == track.source.id) { "所选歌曲身份与目标成员不一致，请重新读取" }
        JSONObject().put("id", track.id).put("title", track.title).put("version", aiMemberVersion(id, track.id))
            .put("position", row.getLong(0)).put("addedAt", row.getLong(1)).put("track", JSONObject(row.getString(2)))
    }
}

internal fun SQLiteDatabase.aiRestoreMembers(id: String, members: List<JSONObject>): List<JSONObject> = members.map { member ->
    val songId = member.getString("id")
    require(!aiMemberExists(id, songId) && aiMemberVersion(id, songId) == member.getLong("version")) { "歌曲关系已被后续操作改变" }
    require(song(songId) != null) { "歌曲记录已被删除，不能恢复成员关系" }
    insertOrThrow("members", null, ContentValues().apply {
        put("playlist_id", id); put("song_id", songId); put("position", member.getLong("position")); put("added_at", member.getLong("addedAt"))
    })
    JSONObject().put("id", songId).put("title", member.optString("title")).put("version", aiMemberVersion(id, songId))
}
