package com.musicone

import org.json.JSONArray
import org.json.JSONObject

internal data class RecommendedTracksSnapshot(val tracks: List<MusicTrack>, val nextPage: Int, val refreshedDay: String)

/** 每日推荐与普通信息流分开缓存，旧版混入分页推荐的快照不再恢复。 */
internal class RecommendedTracksSnapshotStore(cache: MusicDiskCache, namespace: String) {
    private val store = HomeDailySnapshotStore(cache, namespace, "recommended-tracks")

    fun read(): RecommendedTracksSnapshot? = store.read()?.let(::decodeRecommendedTracksSnapshot)

    fun write(snapshot: RecommendedTracksSnapshot) = store.write(encodeRecommendedTracksSnapshot(snapshot))
}

internal fun encodeRecommendedTracksSnapshot(snapshot: RecommendedTracksSnapshot): HomeDailySnapshot =
    HomeDailySnapshot(snapshot.refreshedDay, JSONObject()
        .put("kind", "daily-recommendations").put("version", 2)
        .put("nextPage", 0)
        .put("tracks", JSONArray().apply { snapshot.tracks.forEach { put(it.toStoredTrackJson()) } })
        .toString())

internal fun decodeRecommendedTracksSnapshot(snapshot: HomeDailySnapshot): RecommendedTracksSnapshot? = runCatching {
    val value = JSONObject(snapshot.payload)
    if (value.optString("kind") != "daily-recommendations" || value.optInt("version") < 2) return@runCatching null
    val tracks = value.getJSONArray("tracks").jsonObjects().mapNotNull { it.toStoredTrack() }
    tracks.takeIf { it.isNotEmpty() }?.let {
        RecommendedTracksSnapshot(it, 0, snapshot.refreshedDay)
    }
}.getOrNull()
