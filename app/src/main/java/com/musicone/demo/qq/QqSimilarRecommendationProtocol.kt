package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject

internal data class QqSimilarSong(
    val track: MusicTrack,
)

internal data class QqSimilarRecommendation(
    val baseTrack: MusicTrack,
    val title: String,
    val songs: List<QqSimilarSong>,
)

private val QQ_GENERIC_SIMILAR_TITLES = setOf(
    "猜你也会喜欢",
    "你可能会喜欢",
    "猜你喜欢",
    "相似单曲",
    "相似歌曲",
    "为你推荐",
    "歌曲推荐",
)

internal fun qqSimilarRecommendationRequest(
    baseSongId: Long,
    amount: Int = QQ_SIMILAR_RECOMMENDATION_SIZE,
    from: Int = QQ_SIMILAR_RECOMMENDATION_FROM,
): JSONObject = JSONObject()
    .put("module", "music.recommend.AutoPlayServer")
    .put("method", "GetRadioSimilarSongs")
    .put(
        "param",
        JSONObject()
            .put("vecSong", JSONArray().put(baseSongId))
            .put("from", from)
            .put("amount", amount.coerceIn(1, 12))
            .put("ext", JSONObject()),
    )

internal fun JSONObject.parseQqSimilarRecommendation(
    baseTrack: MusicTrack,
    hasVipAccess: Boolean,
): QqSimilarRecommendation {
    val response = optJSONObject("req_0") ?: optJSONObject("similar") ?: this
    val data = response.optJSONObject("data") ?: response
    val values = data.optJSONArray("vecSong")
        ?: data.optJSONArray("songList")
        ?: data.optJSONArray("tracks")
        ?: JSONArray()
    val songs = buildList<QqSimilarSong> {
        for (index in 0 until values.length()) {
            val item = values.optJSONObject(index) ?: continue
            val trackValue = item.optJSONObject("track")
                ?: item.optJSONObject("Track")
                ?: item.optJSONObject("songInfo")
                ?: item
            val song = runCatching {
                QqSimilarSong(
                    track = trackValue.toQqTrack(hasVipAccess),
                )
            }.getOrNull() ?: continue
            if (song.track.playable && song.track.id != baseTrack.id && none { it.track.id == song.track.id }) {
                add(song)
            }
        }
    }
    val remoteTitle = data.qqSimilarString("recTitle", "title")
    return QqSimilarRecommendation(
        baseTrack = baseTrack,
        title = qqSimilarRecommendationTitle(remoteTitle, baseTrack.title),
        songs = songs,
    )
}

internal fun qqSimilarRecommendationTitle(remoteTitle: String, baseTitle: String): String {
    val decodedTitle = decodeQqPlaylistDescription(remoteTitle)
    val normalizedTitle = decodedTitle.filterNot(Char::isWhitespace)
    val isGenericTitle = normalizedTitle in QQ_GENERIC_SIMILAR_TITLES
    return decodedTitle.takeUnless { it.isBlank() || isGenericTitle }
        ?: "听「${truncateQqRecommendationSeedTitle(baseTitle.ifBlank { "这首歌" })}」也会喜欢"
}

internal fun truncateQqRecommendationSeedTitle(title: String, maxEstimatedUnits: Int = 26): String {
    val resolvedMax = maxEstimatedUnits.coerceAtLeast(2)
    fun Char.estimatedUnits(): Int = if (code in 0x20..0x7E) 1 else 2
    if (title.sumOf { it.estimatedUnits() } <= resolvedMax) return title
    val contentLimit = resolvedMax - 2
    var used = 0
    return buildString {
        for (character in title) {
            val units = character.estimatedUnits()
            if (used + units > contentLimit) break
            append(character)
            used += units
        }
        append('…')
    }
}

private fun JSONObject.qqSimilarString(vararg names: String): String =
    names.firstNotNullOfOrNull { name -> optString(name).takeIf { it.isNotBlank() } }.orEmpty()

internal const val QQ_SIMILAR_RECOMMENDATION_SIZE = 3
internal const val QQ_SIMILAR_RECOMMENDATION_REQUEST_SIZE = 6
internal const val QQ_SIMILAR_RECOMMENDATION_PAGE_COUNT = 3
internal const val QQ_SIMILAR_RECOMMENDATION_FROM = 0
