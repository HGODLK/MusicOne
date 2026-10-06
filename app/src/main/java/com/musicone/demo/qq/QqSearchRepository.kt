package com.musicone.demo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class QqSearchRepository(context: Context) {
    private val preferences = PlatformPreferences(context)
    private val artworkAliases = QqArtworkAliasStore(context)

    suspend fun search(query: String, tab: QqSearchTab, page: Int): QqSearchPage = withContext(Dispatchers.IO) {
        val session = preferences.readSession(MusicSource.QQ)
        val tabs = if (tab == QqSearchTab.ALL) QqSearchTab.entries.drop(1) else listOf(tab)
        val body = envelope(session.credential).put("comm", qqPlaybackComm(session.credential)
            .put("ct", 19).put("cv", 1859).put("v", 1859)
            .put("uin", qqPersonalizedAccountId(session.credential)))
        tabs.forEach { body.put(it.name, qqSearchRequest(query, it, page)) }
        val response = send(body, session.credential)
        tabs.map { response.qqSearchData(it.name).parseQqSearchPage(it, page, session.account?.hasVipAccess == true) }
            .reduce { result, next -> result.copy(singers = result.singers + next.singers,
                albums = result.albums + next.albums, playlists = result.playlists + next.playlists) }
            .let { result ->
                val tracks = QqTrackAccessResolver().resolve(result.songs, session.credential).map(artworkAliases::apply)
                result.copy(songs = artworkAliases.remember(resolveQqSearchArtwork(tracks)),
                    hasMore = tab != QqSearchTab.ALL && result.hasMore)
            }
    }

    suspend fun suggest(query: String): List<String> = withContext(Dispatchers.IO) {
        val cookie = preferences.credential(MusicSource.QQ)
        val request = qqSearchModule("music.smartboxCgi.SmartBoxCgi", "GetSmartBoxResult", JSONObject()
            .put("query", query.trim()).put("search_id", "").put("num_per_page", 20)
            .put("page_idx", 1).put("client_type", 11).put("client_version", 20050009)
            .put("uin", qqCredentialAccountId(cookie)).put("cur_tab", 100))
        send(envelope(cookie).put("suggest", request), cookie).qqSearchData("suggest")
            .optJSONArray("items")?.searchObjects().orEmpty()
            .map { it.searchText("hint", "hint_hilight") }.filter { it.isNotBlank() }.distinct().take(20)
    }

    private fun envelope(cookie: String) = JSONObject().put("comm", qqPlaybackComm(cookie)
        .put("uin", qqPersonalizedAccountId(cookie)).put("cv", 20050009).put("v", 20050009))

    private fun send(body: JSONObject, cookie: String): JSONObject = JSONObject(PlatformHttp.postJson(
        "https://u.y.qq.com/cgi-bin/musicu.fcg", body.toString(), cookie,
        mapOf("Referer" to "https://y.qq.com/", "User-Agent" to "QQMusic 20050009(android 14)"),
    ).text)
}

/** 复用歌单/首页的宽松同名、版本后缀与歌手别名匹配，只借用封面，不替换播放身份。 */
internal fun resolveQqSearchArtwork(tracks: List<MusicTrack>): List<MusicTrack> =
    tracks.map { it.withQqArtworkFallback(tracks) }
