package com.musicone.demo

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** 卡片位置持久化，成员始终由全部本地音乐派生，不另外保存一份可能失步的成员表。 */
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
