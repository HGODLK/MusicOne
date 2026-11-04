package com.musicone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 保存歌单目录，离线重启也能浏览缓存状态。 */
internal class CachedPlaylistStore(context: Context) {
    private val preferences = context.getSharedPreferences("cached_playlists", Context.MODE_PRIVATE)
    fun namespaces(): List<String> = preferences.all.keys
        .filter { it.startsWith("account_directory_v2:") }.map { it.removePrefix("account_directory_v2:") }
    private val parsed = mutableMapOf<String, Pair<String?, List<MusicPlaylist>>>()
    @Synchronized fun read(namespace: String): List<MusicPlaylist> {
        val encoded = preferences.getString("account_directory_v2:$namespace", "[]")
        parsed[namespace]?.takeIf { it.first == encoded }?.let { return it.second }
        val result = runCatching {
            JSONArray(encoded).jsonObjects().map { value ->
                MusicPlaylist(value.getString("id"), value.optString("sourceId").takeIf(String::isNotBlank)?.let(MusicSource::fromId)
                    ?: MusicSource.valueOf(value.getString("source")),
                    value.getString("title"), value.optString("subtitle"), value.optString("description"), value.optInt("count"),
                    value.optLong("start"), value.optLong("end"), value.optString("mark"),
                    value.optJSONArray("tracks").jsonObjects().mapNotNull { it.toStoredTrack() },
                    value.optString("artwork").takeIf(String::isNotBlank),
                    isOwned = value.optBoolean("owned"), isPlatformFavorites = value.optBoolean("platformFavorites"),
                    providerData = value.storedProviderData().toMutableMap().apply {
                        // 旧目录号只迁入不透明数据，不由公共缓存层解释平台业务。
                        if ("qqDirectoryId" !in this && value.optLong("directory") > 0L) {
                            put("qqDirectoryId", value.optLong("directory").toString())
                        }
                        // 最早专辑快照只有旧稳定ID，在读取边界补成通用实体标记。
                        if (value.optString("id").startsWith("qq-album-")) {
                            put("templateEntityKind", "album")
                            put("templateEntityId", value.optString("id").removePrefix("qq-album-"))
                        }
                    }, sourceAccountId = value.optString("accountId").takeIf { it.isNotBlank() && it != "null" },
                    remoteKey = value.optString("remoteKey"))
            }
        }.getOrDefault(emptyList())
        parsed[namespace] = encoded to result
        return result
    }

    @Synchronized fun save(namespace: String, playlists: List<MusicPlaylist>) {
        val values = JSONArray()
        playlists.forEach { playlist ->
            values.put(JSONObject().put("id", playlist.id).put("source", playlist.source.name).put("sourceId", playlist.source.id)
                .put("accountId", playlist.sourceAccountId).put("remoteKey", playlist.remoteKey)
                .put("title", playlist.title).put("subtitle", playlist.subtitle).put("count", playlist.count)
                .put("description", playlist.description).put("owned", playlist.isOwned)
                .put("platformFavorites", playlist.isPlatformFavorites)
                .put("start", playlist.artworkStart).put("end", playlist.artworkEnd).put("mark", playlist.artworkMark)
                .put("artwork", playlist.artworkUrl.orEmpty()).put("providerData", JSONObject(playlist.providerData))
                .put("tracks", JSONArray().apply { playlist.tracks.forEach { put(it.toStoredTrackJson()) } }))
        }
        val encoded = values.toString()
        preferences.edit().putString("account_directory_v2:$namespace", encoded).apply()
        parsed[namespace] = encoded to playlists
    }
}
