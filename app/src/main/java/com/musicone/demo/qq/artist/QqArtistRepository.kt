package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject

internal data class ArtistPage<T>(val items: List<T>, val next: Int?, val total: Int)
internal data class ArtistProfile(val name: String, val artwork: String?, val introduction: String,
    val backgrounds: List<String> = emptyList(), val singerId: Long = 0L, val songTabId: String = "song")

internal class QqArtistRepository(
    private val session: PlatformSession,
    private val artworkAliases: QqArtworkAliasStore,
) {
    private fun request(module: String, method: String, params: JSONObject): JSONObject {
        val comm = JSONObject().apply { qqPersonalizedCommValues(session.credential).forEach { (k, v) -> put(k, v) } }
        if (module == "music.UnifiedHomepage.UnifiedHomepageSrv") comm.put("ct", 11).put("cv", 20080000).put("v", 20080000)
        val body = JSONObject().put("comm", comm).put("artist", qqSearchModule(module, method, params))
        return JSONObject(PlatformHttp.postJson("https://u.y.qq.com/cgi-bin/musicu.fcg", body.toString(),
            session.credential, mapOf("Referer" to "https://y.qq.com/")).text).qqSearchData("artist")
    }

    fun songs(mid: String, begin: Int, sort: ArtistSongSort = ArtistSongSort.Popular): ArtistPage<MusicTrack> {
        val data = request("music.musichallSong.SongListInter", "GetSingerSongList",
            JSONObject().put("singerMid", mid).put("order", sort.order).put("num", 50).put("begin", begin))
        val raw = data.optJSONArray("songList")?.searchObjects().orEmpty()
        val parsed = raw.mapNotNull { runCatching {
            (it.optJSONObject("songInfo") ?: it).toQqTrack(session.account?.hasVipAccess == true)
        }.getOrNull() }
        val items = artworkAliases.remember(
            resolveQqSearchArtwork(QqTrackAccessResolver().resolve(parsed, session.credential).map(artworkAliases::apply)),
        )
        val total = data.optInt("totalNum", begin + raw.size)
        return ArtistPage(items, (begin + raw.size).takeIf { raw.isNotEmpty() && it < total }, total)
    }

    fun albums(mid: String, begin: Int): ArtistPage<MusicPlaylist> {
        val data = request("music.musichallAlbum.AlbumListServer", "GetAlbumList",
            JSONObject().put("singerMid", mid).put("order", 1).put("num", 50).put("begin", begin))
        val raw = data.optJSONArray("albumList")?.searchObjects().orEmpty()
        val items = raw.mapNotNull { artistAlbum(it.optJSONObject("albumInfo") ?: it) }
        val total = data.optInt("total", data.optInt("totalNum", begin + raw.size))
        return ArtistPage(items, (begin + raw.size).takeIf { raw.isNotEmpty() && it < total }, total)
    }

    fun searchSongs(
        singer: QqSearchSinger,
        profile: ArtistProfile,
        query: String,
        offset: Int,
        songTotal: Int,
        sort: ArtistSongSort,
        excludedSongIds: List<String>,
        customInfo: JSONObject?,
    ): ArtistSearchPage {
        val singerId = profile.singerId.takeIf { it > 0L } ?: singer.numericId
        if (singerId <= 0L) throw PlatformApiException("暂时无法获取歌手搜索信息")
        val context = customInfo ?: qqArtistSearchCustomInfo(
            singerId = singerId,
            tabId = profile.songTabId,
            count = songTotal.coerceAtLeast(excludedSongIds.size),
            sort = sort,
            excludedSongIds = excludedSongIds,
        )
        val body = JSONObject()
            .put("comm", qqPlaybackComm(session.credential)
                .put("uin", qqPersonalizedAccountId(session.credential))
                .put("ct", 19).put("cv", 20050009).put("v", 20050009))
            .put("artistSearch", qqArtistSongSearchRequest(query, offset, context))
        val response = JSONObject(PlatformHttp.postJson(
            "https://u.y.qq.com/cgi-bin/musicu.fcg",
            body.toString(),
            session.credential,
            mapOf("Referer" to "https://y.qq.com/", "User-Agent" to "QQMusic 20050009(android 14)"),
        ).text).qqSearchData("artistSearch")
        val page = response.parseQqArtistSearchPage(singer.id, session.account?.hasVipAccess == true)
        val tracks = artworkAliases.remember(resolveQqSearchArtwork(QqTrackAccessResolver().resolve(page.items, session.credential).map(artworkAliases::apply)))
        return page.copy(items = tracks, customInfo = if (page.customInfo.length() > 0) page.customInfo else context)
    }

    fun header(singer: QqSearchSinger): ArtistProfile {
        val header = request("music.UnifiedHomepage.UnifiedHomepageSrv", "GetHomepageHeader",
            JSONObject().put("SingerMid", singer.id)).optJSONObject("Info")
        return artistProfileFromHeader(header, singer, "")
    }

    fun introduction(singer: QqSearchSinger): String {
        val full = runCatching {
            val mid = java.net.URLEncoder.encode(singer.id, "UTF-8")
            artistBiography(PlatformHttp.get("https://c.y.qq.com/splcloud/fcgi-bin/fcg_get_singer_desc.fcg?singermid=$mid&utf8=1&outCharset=utf-8&format=xml",
                headers = mapOf("Referer" to "https://y.qq.com/")).text)
        }.getOrNull()
        if (!full.isNullOrBlank()) return full
        val data = runCatching { request("music.UnifiedHomepage.UnifiedHomepageSrv", "GetHomepageTabDetail",
            JSONObject().put("SingerMid", singer.id).put("IsQueryTabDetail", 1).put("TabID", "wiki")
                .put("PageNum", 0).put("PageSize", 10).put("Order", 0)) }.getOrDefault(JSONObject())
        return data.optJSONObject("IntroductionTab")?.optJSONArray("List")?.searchObjects().orEmpty()
            .flatMap { it.optJSONArray("SingerInfoList")?.searchObjects().orEmpty() }
            .map { it.searchText("Content") }.filter(String::isNotBlank).joinToString("\n\n")
    }
}

/** 头像沿用入口资源；写真按官方 SingerPageGetHeadPicUtils 的 ImageMid 规则解析。 */
internal fun artistProfileFromHeader(header: JSONObject?, singer: QqSearchSinger, intro: String): ArtistProfile {
    val info = header?.optJSONObject("Singer")
    val base = header?.optJSONObject("BaseInfo")
    fun image(value: String?): String? = value?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        ?.replaceFirst("http://", "https://")
    val avatar = image(singer.artwork) ?: image(base?.searchText("Avatar")) ?: image(info?.searchText("SingerPic"))
    val backgrounds = buildList {
        val background = image(base?.searchText("BackgroundImage"))
        val pictureMid = info?.searchText("SingerPMid").orEmpty()
        if (background != null) add(background)
        else if (pictureMid.matches(Regex("[A-Za-z0-9_]+"))) {
            add("https://y.gtimg.cn/music/photo_new/T001R800x800M000$pictureMid.jpg")
        }
        info?.optJSONArray("phone_singer_portrait_list")?.let { list ->
            repeat(list.length()) { image(list.optString(it))?.let(::add) }
        }
        info?.optJSONArray("SingerImageLists")?.searchObjects().orEmpty().forEach { group ->
            group.optJSONArray("ImageList")?.searchObjects().orEmpty().filter { it.optInt("BlockCarousel") != 1 }.forEach { picture ->
                val mid = picture.searchText("ImageMid")
                if (mid.matches(Regex("[A-Za-z0-9_]+"))) {
                    add("https://y.gtimg.cn/music/photo_new/T065R1080x1920M000$mid.jpg")
                } else image(picture.searchText("HighResolutionImage"))?.let(::add)
            }
        }
    }.distinct().sortedBy { url ->
        // 同一头像的不同分辨率也视作头像，优先展示官方写真。
        val imageId = Regex("M000([^/.]+)").find(url)?.groupValues?.get(1)
        val avatarId = avatar?.let { Regex("M000([^/.]+)").find(it)?.groupValues?.get(1) }
        if (url == avatar || (imageId != null && imageId == avatarId)) 2
        else if (url.contains("/T001")) 1 else 0
    }
    val singerId = info?.optLong("SingerID", 0L)?.takeIf { it > 0L } ?: singer.numericId
    return ArtistProfile(info?.searchText("Name").orEmpty().ifBlank { singer.name }, avatar, intro, backgrounds,
        singerId, header?.searchText("TabID").orEmpty().ifBlank { "song" })
}

internal fun artistAlbum(value: JSONObject): MusicPlaylist? {
    val mid = value.searchText("albumMid", "album_mid", "mid")
    val title = value.searchText("albumName", "album_name", "name")
    if (mid.isBlank() || title.isBlank()) return null
    val colors = qqArtworkColors(mid)
    return MusicPlaylist(QQ_SEARCH_ALBUM_PREFIX + mid, MusicSource.QQ, title,
        value.searchText("singerName"), value.searchText("publishDate", "pub_time"), value.optInt("totalNum"),
        colors.first, colors.second, title.take(1), emptyList(),
        "https://y.gtimg.cn/music/photo_new/T002R500x500M000$mid.jpg")
}

internal fun MusicTrack.relatedAlbum(): MusicPlaylist? = albumMid.takeIf(String::isNotBlank)?.let {
    MusicPlaylist(QQ_SEARCH_ALBUM_PREFIX + it, source, album, artists, "", 0,
        artworkStart, artworkEnd, artworkMark, emptyList(), artworkUrl)
}
