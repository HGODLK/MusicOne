package com.musicone

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 音乐库独立保存，各音源的目录缓存、登录和播放缓存由对应存储管理。 */
internal class MusicLibraryDatabase(context: Context) : SQLiteOpenHelper(context, "musicone_library.db", null, 3) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE songs (id TEXT PRIMARY KEY, source TEXT NOT NULL, data TEXT NOT NULL, title_override TEXT, artist_override TEXT, artwork_override TEXT, lyric_path TEXT, default_lyric_path TEXT, lyric_offset INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE playlists (id TEXT PRIMARY KEY, title TEXT NOT NULL, position INTEGER NOT NULL, remote_folder INTEGER, owner TEXT, manual_order INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE members (playlist_id TEXT NOT NULL REFERENCES playlists(id) ON DELETE CASCADE, song_id TEXT NOT NULL REFERENCES songs(id), position INTEGER NOT NULL, added_at INTEGER NOT NULL DEFAULT 0, remote_member INTEGER NOT NULL DEFAULT 0, hidden INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(playlist_id,song_id))")
        db.execSQL("CREATE TABLE folders (id TEXT PRIMARY KEY, uri TEXT UNIQUE NOT NULL, title TEXT NOT NULL, copy_files INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE locations (song_id TEXT NOT NULL REFERENCES songs(id), uri TEXT UNIQUE NOT NULL, folder_id TEXT, relative_path TEXT NOT NULL DEFAULT '', size INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(song_id,uri))")
        db.execSQL("INSERT INTO playlists(id,title,position) VALUES('$UNIFIED_PLAYLIST_ID','综合歌单',0)")
        addSourceIdentity(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE members ADD COLUMN added_at INTEGER NOT NULL DEFAULT 0")
            // 旧库没有真实时间，只保留插入先后，后续新增使用实际时间。
            db.execSQL("UPDATE members SET added_at=rowid")
        }
        if (oldVersion < 3) addSourceIdentity(db)
    }

    private fun addSourceIdentity(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE playlists ADD COLUMN source_id TEXT")
        db.execSQL("ALTER TABLE playlists ADD COLUMN source_account TEXT")
        db.execSQL("ALTER TABLE playlists ADD COLUMN source_collection TEXT")
        db.execSQL("ALTER TABLE playlists ADD COLUMN source_revision INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE members ADD COLUMN source_member TEXT")
        db.execSQL("ALTER TABLE songs ADD COLUMN album_override TEXT")
        // 只在旧库升级时识别历史字段，运行时订阅不再解释平台身份。
        db.execSQL("UPDATE playlists SET source_id='com.musicone.source.bilibili',source_account=owner,source_collection=CAST(remote_folder AS TEXT) WHERE remote_folder IS NOT NULL")
        db.rawQuery("SELECT id,data FROM songs WHERE source='BILIBILI'", null).use { rows ->
            while (rows.moveToNext()) {
                val data = runCatching { org.json.JSONObject(rows.getString(1)) }.getOrNull() ?: continue
                val member = data.optString("bilibiliBvid").ifBlank { data.optJSONObject("providerData")?.optString("bilibiliBvid").orEmpty() }
                if (member.isNotBlank()) db.execSQL("UPDATE members SET source_member=? WHERE song_id=? AND remote_member=1", arrayOf(member, rows.getString(0)))
            }
        }
    }
}
