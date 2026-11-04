package com.musicone

import org.json.JSONArray
import org.json.JSONObject

/** 只缓存可展示的元数据，不落盘播放票据或登录凭证。 */
internal class MainSourceFeedSnapshotStore(private val cache: MusicDiskCache, private val namespace: String) {
    private val dailyStore = HomeDailySnapshotStore(cache, namespace, "music-feed")
    var refreshedDay: String? = null
        private set

    fun read(): List<MainSourceHomeCard> = runCatching {
        val snapshot = dailyStore.read()
        refreshedDay = snapshot?.refreshedDay
        val raw = snapshot?.payload ?: cache.read("feed:v3:$namespace")?.let { String(it, Charsets.UTF_8) }
            ?: return emptyList()
        val values = JSONArray(raw).jsonObjects()
        // 上一版合并丢失了不同货架的标题，旧快照无法本地拆回三条；保留旧画面并触发刷新恢复。
        if (requiresMainSourceFeedShelfLayoutRefresh(values)) refreshedDay = null
        values.mapNotNull { value ->
            when (value.optString("kind")) {
                "song" -> value.optJSONObject("track")?.toStoredTrack()?.let {
                    MainSourceHomeCard.Song(
                        it,
                        value.optString("title"),
                        value.optString("query").takeIf(String::isNotBlank),
                        value.mainSourceHomeSectionKind(),
                    )
                }
                "playlist" -> value.optJSONObject("playlist")?.let {
                    MainSourceHomeCard.Playlist(it.toFeedPlaylist(MusicSource.valueOf(namespace.substringBefore(':'))), value.mainSourceHomeSectionKind())
                }
                "song-group" -> value.optJSONArray("tracks")?.toFeedSongs(value.optJSONArray("titles"))?.let {
                    MainSourceHomeCard.SongGroup(value.optString("title"), it.map { song -> song.track },
                        it.map { song -> song.recommendationTitle }, value.mainSourceHomeSectionKind())
                }
                "song-shelf" -> value.optJSONArray("pages")?.toFeedPages()?.let { pages ->
                    // 旧版本未保存跨页合并契约，不能把损坏的货架数量恢复成当前声明；由网络刷新补回。
                    if (value.optInt("shelfLayoutVersion") < 3) return@mapNotNull null
                    MainSourceHomeCard.SongShelf(
                        value.optString("shelfId"),
                        value.optString("title"),
                        pages,
                        value.mainSourceHomeSectionKind(MainSourceHomeSectionKind.SONG_RECOMMENDATION),
                        mergePages = value.optBoolean("mergePages", true),
                        stableIdentity = value.optString("stableIdentity").takeIf(String::isNotBlank),
                        groupId = value.optString("groupId").takeIf(String::isNotBlank),
                        groupLimit = value.optInt("groupLimit").takeIf { it > 0 },
                    )
                }
                else -> null
            }
        }.let(::normalizeMainSourceHomeCards)
    }.getOrDefault(emptyList())

    fun write(cards: List<MainSourceHomeCard>, day: String? = refreshedDay) {
        val array = JSONArray()
        normalizeMainSourceHomeCards(cards)
            .take(40)
            .forEach { card ->
            when (card) {
                is MainSourceHomeCard.Song -> array.put(JSONObject().put("kind", "song")
                    .put("track", card.track.toStoredTrackJson()).put("title", card.recommendationTitle)
                    .put("query", card.lookupQuery.orEmpty()).put("section", card.section.name))
                is MainSourceHomeCard.Playlist -> array.put(JSONObject().put("kind", "playlist")
                    .put("playlist", card.playlist.toFeedJson()).put("section", card.section.name))
                is MainSourceHomeCard.SongGroup -> array.put(JSONObject().put("kind", "song-group")
                    .put("title", card.recommendationTitle)
                    .put("tracks", card.tracks.toFeedSongs(card.trackRecommendationTitles))
                    .put("section", card.section.name))
                is MainSourceHomeCard.SongShelf -> array.put(JSONObject().put("kind", "song-shelf")
                    .put("shelfId", card.shelfId).put("title", card.title)
                    .put("shelfLayoutVersion", 3).put("mergePages", card.mergePages)
                    .put("stableIdentity", card.stableIdentity.orEmpty()).put("groupId", card.groupId.orEmpty())
                    .put("groupLimit", card.groupLimit ?: 0)
                    .put("pages", card.pages.toFeedPages()).put("section", card.section.name))
            }
        }
        dailyStore.write(HomeDailySnapshot(day.orEmpty(), array.toString()))
        refreshedDay = day
    }
}

internal fun requiresMainSourceFeedShelfLayoutRefresh(values: List<JSONObject>): Boolean = values.any {
    it.optString("kind") == "song-shelf" && it.optInt("shelfLayoutVersion") < 3
}

private fun JSONObject.mainSourceHomeSectionKind(
    fallback: MainSourceHomeSectionKind = MainSourceHomeSectionKind.MUSIC_FLOW,
): MainSourceHomeSectionKind = runCatching {
    MainSourceHomeSectionKind.valueOf(optString("section"))
}.getOrDefault(fallback)

private fun List<MusicTrack>.toFeedSongs(titles: List<String> = emptyList()): JSONArray = JSONArray().apply {
    forEachIndexed { index, track ->
        put(JSONObject().put("track", track.toStoredTrackJson())
            .put("title", titles.getOrNull(index).orEmpty()))
    }
}

private fun JSONArray.toFeedSongs(titles: JSONArray?): List<MainSourceHomeCard.Song> = buildList {
    for (index in 0 until length()) {
        val value = optJSONObject(index) ?: continue
        value.optJSONObject("track")?.toStoredTrack()?.let { track ->
            add(MainSourceHomeCard.Song(track, value.optString("title").ifBlank {
                titles?.optString(index).orEmpty()
            }))
        }
    }
}

private fun List<List<MainSourceHomeCard.Song>>.toFeedPages(): JSONArray = JSONArray().apply {
    forEach { page ->
        put(JSONArray().apply {
            page.forEach { song ->
                put(JSONObject().put("track", song.track.toStoredTrackJson())
                    .put("title", song.recommendationTitle))
            }
        })
    }
}

private fun JSONArray.toFeedPages(): List<List<MainSourceHomeCard.Song>> = buildList {
    for (index in 0 until length()) optJSONArray(index)?.toFeedSongs(null)?.let(::add)
}

private fun MusicPlaylist.toFeedJson() = JSONObject().put("id", id).put("sourceId", source.id).put("source", source.name).put("title", title)
    .put("subtitle", subtitle).put("description", description).put("count", count)
    .put("start", artworkStart).put("end", artworkEnd).put("mark", artworkMark).put("artwork", artworkUrl.orEmpty())
    .put("owned", isOwned).put("favorites", isPlatformFavorites).put("providerData", JSONObject(providerData))
    .put("accountId", sourceAccountId).put("remoteKey", remoteKey)

private fun JSONObject.toFeedPlaylist(legacySource: MusicSource) = MusicPlaylist(optString("id"),
    optString("sourceId").takeIf(String::isNotBlank)?.let(MusicSource::fromId) ?: legacySource, optString("title"),
    optString("subtitle"), optString("description"), optInt("count"), optLong("start"), optLong("end"),
    optString("mark"), emptyList(), optString("artwork").takeIf(String::isNotBlank),
    isOwned = optBoolean("owned"), isPlatformFavorites = optBoolean("favorites"), providerData = storedProviderData(),
    sourceAccountId = optString("accountId").takeIf { it.isNotBlank() && it != "null" }, remoteKey = optString("remoteKey"))
