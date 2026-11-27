package com.musicone

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/** 只顺手补全缺失的默认封面，不动用户自己改过的自定义信息。 */
internal fun SQLiteDatabase.completeLibraryArtwork(incoming: MusicTrack) {
    val image = incoming.artworkUrl?.takeIf(String::isNotBlank) ?: return
    rawQuery("SELECT data,artwork_override FROM songs WHERE id=?", arrayOf(incoming.id)).use { row ->
        if (!row.moveToFirst() || !row.getString(1).isNullOrBlank()) return
        val stored = JSONObject(row.getString(0)).toStoredTrack() ?: return
        if (!stored.artworkUrl.isNullOrBlank()) return
        update("songs", ContentValues().apply {
            put("data", stored.copy(artworkUrl = image).toStoredTrackJson().toString())
        }, "id=?", arrayOf(incoming.id))
    }
}
