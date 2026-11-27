package com.musicone

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** “手机存储”歌单只存一条卡片位置记录，里面的歌曲始终动态取全部本地音乐，不单独维护成员表防不同步。 */
internal fun SQLiteDatabase.ensurePhoneStoragePlaylist() {
    val exists = rawQuery("SELECT 1 FROM playlists WHERE id=?", arrayOf(PHONE_STORAGE_PLAYLIST_ID)).use { it.moveToFirst() }
    if (exists) return
    val hasLocal = rawQuery("SELECT 1 FROM songs WHERE source=? LIMIT 1", arrayOf(MusicSource.LOCAL.name))
        .use { it.moveToFirst() }
    if (!hasLocal) return
    insertWithOnConflict("playlists", null, ContentValues().apply {
        put("id", PHONE_STORAGE_PLAYLIST_ID)
        put("title", "手机存储")
        put("position", System.currentTimeMillis())
    }, SQLiteDatabase.CONFLICT_IGNORE)
}
