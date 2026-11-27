package com.musicone.source.kugou

import com.musicone.source.sdk.*
import org.json.JSONObject
import org.json.JSONArray

internal const val KUGOU_HOME_PLAYLIST_LIMIT = 16
internal const val KUGOU_HOME_PLAYLIST_TITLE = "这里有你想听的歌单"

/** 截断服务端多返回的推荐项，保证首页每批只展示固定数量。 */
internal fun KugouHomePlaylistPage.asKugouHomeBatch(): KugouHomePlaylistPage {
    val selected = playlists.take(KUGOU_HOME_PLAYLIST_LIMIT)
    val ids = selected.mapTo(mutableSetOf()) { it.id }
    return copy(playlists = selected, nextPage = null, metadata = metadata.filterKeys { it in ids })
}

/** 把首页推荐数据拆成单曲推荐货架和推荐歌单卡片两组分区。 */
internal fun KugouHomePlaylistPage.homeSections(
    account: String?,
    remember: (MusicPlaylist) -> MusicPlaylist,
    recommendations: KugouHomeRecommendations? = null,
): List<SourceHomeSection> = buildList {
    if (recommendations != null && recommendations.tracks.isNotEmpty()) {
        add(SourceHomeSection("kugou.personal-recommendations", recommendations.title,
            tracks = recommendations.tracks.map { KugouSourceMapping.track(it, account) },
            payload = SourcePayload("musicone.home-section", 1, JSONObject().put("layout", "track-shelf")
                .put("recommendation", true)
                .put("pageSizes", JSONArray(recommendations.tracks.chunked(3).map { it.size })).toString())))
    }
    playlists.forEach { playlist ->
        // 接口自带的 withsong 只是前几首预览，这里清空 tracks，避免被详情页误当成完整歌单
        val collection = KugouSourceMapping.collection(remember(playlist.copy(tracks = emptyList())), account)
        val data = JSONObject(collection.payload.encoded)
        metadata[playlist.id].orEmpty().forEach { (key, value) -> data.put(key, value) }
        add(SourceHomeSection("kugou.playlist:${playlist.id}", KUGOU_HOME_PLAYLIST_TITLE,
            collections = listOf(collection.copy(payload = collection.payload.copy(encoded = data.toString()))),
            payload = SourcePayload("musicone.home-section", 1, "{\"layout\":\"collection-card\"}")))
    }
}
