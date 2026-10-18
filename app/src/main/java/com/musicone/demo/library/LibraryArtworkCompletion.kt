package com.musicone.demo

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/** 只补齐默认封面，不能把显示层的自定义资料写回默认资料。 */
internal fun SQLiteDatabase.completeLibraryArtwork(incoming: MusicTrack) {
    val image = incoming.artworkUrl?.takeIf(String::isNotBlank) ?: return
    rawQuery("SELECT data,artwork_override FROM songs WHERE id=?", arrayOf(incoming.id)).use { row ->
        if (!row.moveToFirst() || !row.getString(1).isNullOrBlank()) return
        val stored = JSONObject(row.getString(0)).toQqStoredTrack() ?: return
        if (!stored.artworkUrl.isNullOrBlank()) return
        update("songs", ContentValues().apply {
            put("data", stored.copy(artworkUrl = image).toQqStoredTrackJson().toString())
        }, "id=?", arrayOf(incoming.id))
    }
}
