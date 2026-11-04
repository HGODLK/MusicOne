package com.musicone

import org.json.JSONArray
import org.json.JSONObject

internal data class MainSourceSimilarRecommendationSnapshot(val pages: List<MainSourceSimilarRecommendation>, val refreshedDay: String)

/** 相似推荐保存完整标题、种子和歌曲，同一天重启后继续显示原推荐。 */
internal class MainSourceSimilarRecommendationSnapshotStore(cache: MusicDiskCache, namespace: String) {
    private val store = HomeDailySnapshotStore(cache, namespace, "similar-recommendations")

    fun read(): MainSourceSimilarRecommendationSnapshot? = store.read()?.let(::decodeMainSourceSimilarRecommendationSnapshot)

    fun write(snapshot: MainSourceSimilarRecommendationSnapshot) = store.write(encodeMainSourceSimilarRecommendationSnapshot(snapshot))
}

internal fun encodeMainSourceSimilarRecommendationSnapshot(snapshot: MainSourceSimilarRecommendationSnapshot): HomeDailySnapshot =
    HomeDailySnapshot(snapshot.refreshedDay, JSONArray().apply {
        snapshot.pages.forEach { page ->
            put(JSONObject().put("base", page.baseTrack.toStoredTrackJson()).put("title", page.title)
                .put("songs", JSONArray().apply { page.songs.forEach { put(it.track.toStoredTrackJson()) } }))
        }
    }.toString())

internal fun decodeMainSourceSimilarRecommendationSnapshot(snapshot: HomeDailySnapshot): MainSourceSimilarRecommendationSnapshot? = runCatching {
    val pages = JSONArray(snapshot.payload).jsonObjects().mapNotNull { value ->
        val base = value.optJSONObject("base")?.toStoredTrack() ?: return@mapNotNull null
        val songs = value.optJSONArray("songs")?.jsonObjects().orEmpty()
            .mapNotNull { it.toStoredTrack()?.let(::MainSourceSimilarSong) }
        songs.takeIf { it.isNotEmpty() }?.let { MainSourceSimilarRecommendation(base, value.optString("title"), it) }
    }
    pages.takeIf { it.isNotEmpty() }?.let { MainSourceSimilarRecommendationSnapshot(it, snapshot.refreshedDay) }
}.getOrNull()
