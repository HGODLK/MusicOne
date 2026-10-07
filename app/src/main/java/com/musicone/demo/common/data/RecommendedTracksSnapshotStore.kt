package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject

internal data class RecommendedTracksSnapshot(val tracks: List<MusicTrack>, val nextPage: Int, val refreshedDay: String)

/** 推荐歌曲保留上次成功内容和下一批索引，重启后手动刷新仍能继续换一批。 */
internal class RecommendedTracksSnapshotStore(cache: MusicDiskCache, namespace: String) {
    private val store = HomeDailySnapshotStore(cache, namespace, "recommended-tracks")

    fun read(): RecommendedTracksSnapshot? = store.read()?.let(::decodeRecommendedTracksSnapshot)

    fun write(snapshot: RecommendedTracksSnapshot) = store.write(encodeRecommendedTracksSnapshot(snapshot))
}

internal fun encodeRecommendedTracksSnapshot(snapshot: RecommendedTracksSnapshot): HomeDailySnapshot =
    HomeDailySnapshot(snapshot.refreshedDay, JSONObject()
        .put("nextPage", snapshot.nextPage)
        .put("tracks", JSONArray().apply { snapshot.tracks.forEach { put(it.toQqStoredTrackJson()) } })
        .toString())

internal fun decodeRecommendedTracksSnapshot(snapshot: HomeDailySnapshot): RecommendedTracksSnapshot? = runCatching {
    val value = JSONObject(snapshot.payload)
    val tracks = value.getJSONArray("tracks").searchObjects().mapNotNull { it.toQqStoredTrack() }
    tracks.takeIf { it.isNotEmpty() }?.let {
        RecommendedTracksSnapshot(it, value.optInt("nextPage", 0).coerceAtLeast(0), snapshot.refreshedDay)
    }
}.getOrNull()
