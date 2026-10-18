package com.musicone.demo

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 音乐库独立保存，QQ 的目录缓存、登录和播放缓存继续使用原存储。 */
internal class MusicLibraryDatabase(context: Context) : SQLiteOpenHelper(context, "musicone_library.db", null, 2) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE songs (id TEXT PRIMARY KEY, source TEXT NOT NULL, data TEXT NOT NULL, title_override TEXT, artist_override TEXT, artwork_override TEXT, lyric_path TEXT, default_lyric_path TEXT, lyric_offset INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE playlists (id TEXT PRIMARY KEY, title TEXT NOT NULL, position INTEGER NOT NULL, remote_folder INTEGER, owner TEXT, manual_order INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE members (playlist_id TEXT NOT NULL REFERENCES playlists(id) ON DELETE CASCADE, song_id TEXT NOT NULL REFERENCES songs(id), position INTEGER NOT NULL, added_at INTEGER NOT NULL DEFAULT 0, remote_member INTEGER NOT NULL DEFAULT 0, hidden INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(playlist_id,song_id))")
        db.execSQL("CREATE TABLE folders (id TEXT PRIMARY KEY, uri TEXT UNIQUE NOT NULL, title TEXT NOT NULL, copy_files INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE locations (song_id TEXT NOT NULL REFERENCES songs(id), uri TEXT UNIQUE NOT NULL, folder_id TEXT, relative_path TEXT NOT NULL DEFAULT '', size INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(song_id,uri))")
        db.execSQL("INSERT INTO playlists(id,title,position) VALUES('$UNIFIED_PLAYLIST_ID','综合歌单',0)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE members ADD COLUMN added_at INTEGER NOT NULL DEFAULT 0")
            // 旧库没有真实时间，只保留插入先后，后续新增使用实际时间。
            db.execSQL("UPDATE members SET added_at=rowid")
        }
    }
}
