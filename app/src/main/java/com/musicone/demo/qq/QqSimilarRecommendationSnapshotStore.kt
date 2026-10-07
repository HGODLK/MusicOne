package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject

internal data class QqSimilarRecommendationSnapshot(val pages: List<QqSimilarRecommendation>, val refreshedDay: String)

/** 相似推荐保存完整标题、种子和歌曲，同一天重启后继续显示原推荐。 */
internal class QqSimilarRecommendationSnapshotStore(cache: MusicDiskCache, namespace: String) {
    private val store = HomeDailySnapshotStore(cache, namespace, "similar-recommendations")

    fun read(): QqSimilarRecommendationSnapshot? = store.read()?.let(::decodeQqSimilarRecommendationSnapshot)

    fun write(snapshot: QqSimilarRecommendationSnapshot) = store.write(encodeQqSimilarRecommendationSnapshot(snapshot))
}

internal fun encodeQqSimilarRecommendationSnapshot(snapshot: QqSimilarRecommendationSnapshot): HomeDailySnapshot =
    HomeDailySnapshot(snapshot.refreshedDay, JSONArray().apply {
        snapshot.pages.forEach { page ->
            put(JSONObject().put("base", page.baseTrack.toQqStoredTrackJson()).put("title", page.title)
                .put("songs", JSONArray().apply { page.songs.forEach { put(it.track.toQqStoredTrackJson()) } }))
        }
    }.toString())

internal fun decodeQqSimilarRecommendationSnapshot(snapshot: HomeDailySnapshot): QqSimilarRecommendationSnapshot? = runCatching {
    val pages = JSONArray(snapshot.payload).searchObjects().mapNotNull { value ->
        val base = value.optJSONObject("base")?.toQqStoredTrack() ?: return@mapNotNull null
        val songs = value.optJSONArray("songs")?.searchObjects().orEmpty()
            .mapNotNull { it.toQqStoredTrack()?.let(::QqSimilarSong) }
        songs.takeIf { it.isNotEmpty() }?.let { QqSimilarRecommendation(base, value.optString("title"), it) }
    }
    pages.takeIf { it.isNotEmpty() }?.let { QqSimilarRecommendationSnapshot(it, snapshot.refreshedDay) }
}.getOrNull()
