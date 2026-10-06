package com.musicone.demo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 保存歌单目录，离线重启也能浏览缓存状态。 */
internal class CachedPlaylistStore(context: Context) {
    private val preferences = context.getSharedPreferences("cached_playlists", Context.MODE_PRIVATE)
    fun read(namespace: String): List<MusicPlaylist> = runCatching {
        JSONArray(preferences.getString(namespace, "[]")).searchObjects().map { value ->
            MusicPlaylist(value.getString("id"), MusicSource.valueOf(value.getString("source")),
                value.getString("title"), value.optString("subtitle"), "", value.optInt("count"),
                value.optLong("start"), value.optLong("end"), value.optString("mark"),
                value.optJSONArray("tracks")?.searchObjects().orEmpty().mapNotNull { it.toQqStoredTrack() },
                value.optString("artwork").takeIf(String::isNotBlank),
                value.optLong("directory").takeIf { it > 0 })
        }
    }.getOrDefault(emptyList())

    fun save(namespace: String, playlists: List<MusicPlaylist>) {
        val values = JSONArray()
        playlists.forEach { playlist ->
            values.put(JSONObject().put("id", playlist.id).put("source", playlist.source.name)
                .put("title", playlist.title).put("subtitle", playlist.subtitle).put("count", playlist.count)
                .put("start", playlist.artworkStart).put("end", playlist.artworkEnd).put("mark", playlist.artworkMark)
                .put("artwork", playlist.artworkUrl.orEmpty()).put("directory", playlist.qqDirectoryId ?: 0)
                .put("tracks", JSONArray().apply { playlist.tracks.forEach { put(it.toQqStoredTrackJson()) } }))
        }
        preferences.edit().putString(namespace, values.toString()).apply()
    }
}
