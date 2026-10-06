package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject

/** 只缓存可展示的元数据，不落盘播放票据或登录凭证。 */
internal class QqFeedSnapshotStore(private val cache: MusicDiskCache, private val namespace: String) {
    fun read(): List<QqMusicFeedCard> = runCatching {
        val bytes = cache.read("feed:v3:$namespace") ?: return emptyList()
        JSONArray(String(bytes, Charsets.UTF_8)).searchObjects().mapNotNull { value ->
            when (value.optString("kind")) {
                "song" -> value.optJSONObject("track")?.toQqStoredTrack()?.let {
                    QqMusicFeedCard.Song(
                        it,
                        value.optString("title"),
                        value.optString("query").takeIf(String::isNotBlank),
                        value.qqMusicFeedSection(),
                    )
                }
                "playlist" -> value.optJSONObject("playlist")?.let {
                    QqMusicFeedCard.Playlist(it.toFeedPlaylist(), value.qqMusicFeedSection())
                }
                "song-group" -> value.optJSONArray("tracks")?.toFeedSongs(value.optJSONArray("titles"))?.let {
                    QqMusicFeedCard.SongGroup(value.optString("title"), it.map { song -> song.track },
                        it.map { song -> song.recommendationTitle }, value.qqMusicFeedSection())
                }
                "song-shelf" -> value.optJSONArray("pages")?.toFeedPages()?.let { pages ->
                    QqMusicFeedCard.SongShelf(
                        value.optString("shelfId"),
                        value.optString("title"),
                        pages,
                        value.qqMusicFeedSection(),
                    )
                }
                else -> null
            }
        }.filterNot { card ->
            card is QqMusicFeedCard.SongShelf && card.title.isQqFeedHiddenSnapshotShelf()
        }.filterNot { card ->
            card is QqMusicFeedCard.SongGroup && !hasQqMusicFeedMixedAlbums(card.tracks)
        }.let(::normalizeQqMusicFlowCards)
    }.getOrDefault(emptyList())

    fun write(cards: List<QqMusicFeedCard>) {
        val array = JSONArray()
        normalizeQqMusicFlowCards(cards)
            .filterNot { card ->
                card is QqMusicFeedCard.SongShelf && card.title.isQqFeedHiddenSnapshotShelf()
            }
            .filterNot { card ->
                card is QqMusicFeedCard.SongGroup && !hasQqMusicFeedMixedAlbums(card.tracks)
            }
            .take(40)
            .forEach { card ->
            when (card) {
                is QqMusicFeedCard.Song -> array.put(JSONObject().put("kind", "song")
                    .put("track", card.track.toQqStoredTrackJson()).put("title", card.recommendationTitle)
                    .put("query", card.lookupQuery.orEmpty()).put("section", card.section.name))
                is QqMusicFeedCard.Playlist -> array.put(JSONObject().put("kind", "playlist")
                    .put("playlist", card.playlist.toFeedJson()).put("section", card.section.name))
                is QqMusicFeedCard.SongGroup -> array.put(JSONObject().put("kind", "song-group")
                    .put("title", card.recommendationTitle)
                    .put("tracks", card.tracks.toFeedSongs(card.trackRecommendationTitles))
                    .put("section", card.section.name))
                is QqMusicFeedCard.SongShelf -> array.put(JSONObject().put("kind", "song-shelf")
                    .put("shelfId", card.shelfId).put("title", card.title)
                    .put("pages", card.pages.toFeedPages()).put("section", card.section.name))
            }
        }
        cache.write("feed:v3:$namespace", array.toString().toByteArray(Charsets.UTF_8))
    }
}

/** 版本升级前的缓存可能仍有已下线的节目/VIP货架，读取时也必须清理。 */
private fun String.isQqFeedHiddenSnapshotShelf(): Boolean = replace(Regex("\\s+"), "")
    .let { it == "今日专属精彩节目" || it.startsWith("VIP专属歌曲推荐") }

private fun JSONObject.qqMusicFeedSection(): QqMusicFeedSection = runCatching {
    QqMusicFeedSection.valueOf(optString("section"))
}.getOrDefault(QqMusicFeedSection.MUSIC_FLOW)

private fun List<MusicTrack>.toFeedSongs(titles: List<String> = emptyList()): JSONArray = JSONArray().apply {
    forEachIndexed { index, track ->
        put(JSONObject().put("track", track.toQqStoredTrackJson())
            .put("title", titles.getOrNull(index).orEmpty()))
    }
}

private fun JSONArray.toFeedSongs(titles: JSONArray?): List<QqMusicFeedCard.Song> = buildList {
    for (index in 0 until length()) {
        val value = optJSONObject(index) ?: continue
        value.optJSONObject("track")?.toQqStoredTrack()?.let { track ->
            add(QqMusicFeedCard.Song(track, value.optString("title").ifBlank {
                titles?.optString(index).orEmpty()
            }))
        }
    }
}

private fun List<List<QqMusicFeedCard.Song>>.toFeedPages(): JSONArray = JSONArray().apply {
    forEach { page ->
        put(JSONArray().apply {
            page.forEach { song ->
                put(JSONObject().put("track", song.track.toQqStoredTrackJson())
                    .put("title", song.recommendationTitle))
            }
        })
    }
}

private fun JSONArray.toFeedPages(): List<List<QqMusicFeedCard.Song>> = buildList {
    for (index in 0 until length()) optJSONArray(index)?.toFeedSongs(null)?.let(::add)
}

private fun MusicPlaylist.toFeedJson() = JSONObject().put("id", id).put("title", title)
    .put("subtitle", subtitle).put("description", description).put("count", count)
    .put("start", artworkStart).put("end", artworkEnd).put("mark", artworkMark).put("artwork", artworkUrl.orEmpty())

private fun JSONObject.toFeedPlaylist() = MusicPlaylist(optString("id"), MusicSource.QQ, optString("title"),
    optString("subtitle"), optString("description"), optInt("count"), optLong("start"), optLong("end"),
    optString("mark"), emptyList(), optString("artwork").takeIf(String::isNotBlank))
