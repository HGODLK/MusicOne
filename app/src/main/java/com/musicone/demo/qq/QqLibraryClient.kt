package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject

internal class QqLibraryClient {
    fun favoritePlaylist(cookie: String, userId: String, hasVipAccess: Boolean): MusicPlaylist {
        requireLogin(cookie, userId)
        val response = JSONObject(PlatformHttp.get(
            "$PROFILE_ASSET_API?${profileAssetQuery(userId, "1", 0, 500)}",
            cookie,
            webHeaders(),
        ).text.qqLibraryJson())
        requireSuccess(response, "我喜欢的音乐加载失败")
        val data = response.optJSONObject("data") ?: JSONObject()
        val tracks = data.optJSONArray("songlist").qqLibraryObjects { item ->
            (item.optJSONObject("data") ?: item).toQqTrack(hasVipAccess)
        }
        val first = tracks.firstOrNull()
        return MusicPlaylist(
            id = FAVORITES_ID,
            source = MusicSource.QQ,
            title = "我喜欢的音乐",
            subtitle = "QQ 音乐",
            description = "你在 QQ 音乐收藏的歌曲",
            count = data.optInt("totalsong").takeIf { it > 0 } ?: tracks.size,
            artworkStart = first?.artworkStart ?: 0xFF71DCC1,
            artworkEnd = first?.artworkEnd ?: 0xFF2A7F72,
            artworkMark = "喜",
            tracks = QqTrackAccessResolver().resolve(tracks, cookie),
            artworkUrl = data.qqCoverUrl() ?: first?.artworkUrl,
        )
    }

    fun createdPlaylists(cookie: String, userId: String): List<MusicPlaylist> {
        requireLogin(cookie, userId)
        val query = linkedMapOf(
            "hostuin" to userId,
            "sin" to "0",
            "size" to "100",
            "format" to "json",
            "inCharset" to "utf8",
            "outCharset" to "utf-8",
        ).asQuery()
        val response = JSONObject(PlatformHttp.get("$CREATED_PLAYLIST_API?$query", cookie, webHeaders()).text.qqLibraryJson())
        requireSuccess(response, "创建的歌单加载失败")
        val data = response.optJSONObject("data") ?: JSONObject()
        return buildList {
            data.optJSONArray("disslist").qqLibraryObjects { item -> createdPlaylist(item, userId) }.forEach(::add)
            data.optJSONArray("list").qqLibraryObjects { item -> legacyCreatedPlaylist(item, userId) }.forEach(::add)
        }.distinctBy(MusicPlaylist::id).filterNot(MusicPlaylist::isQqHiddenCreatedPlaylist)
    }

    fun collectedPlaylists(cookie: String, userId: String): List<MusicPlaylist> {
        requireLogin(cookie, userId)
        val response = JSONObject(PlatformHttp.get(
            "$PROFILE_ASSET_API?${profileAssetQuery(userId, "3", 0, 100)}",
            cookie,
            webHeaders(),
        ).text.qqLibraryJson())
        requireSuccess(response, "收藏的歌单加载失败")
        return response.optJSONObject("data")?.optJSONArray("cdlist").qqLibraryObjects { item ->
            val id = item.optString("dissid").trim()
            val title = item.optString("dissname").trim()
            if (id.isBlank() || title.isBlank()) throw IllegalArgumentException("无效歌单")
            playlist(
                remoteId = id,
                title = title,
                subtitle = item.optString("nickname").ifBlank { "收藏的歌单" },
                description = "收藏自 QQ 音乐",
                count = item.optInt("songnum"),
                cover = item.qqCoverUrl().orEmpty(),
            )
        }.distinctBy(MusicPlaylist::id).filterNot(MusicPlaylist::isQqFavoritesShortcut)
    }

    fun profileDirectoryDetail(
        playlist: MusicPlaylist,
        cookie: String,
        hasVipAccess: Boolean,
    ): MusicPlaylist {
        val dirId = playlist.remoteId().removePrefix(PROFILE_DIRECTORY_PREFIX)
        val values = cookie.cookieValues()
        val uin = values["euin"].orEmpty().ifBlank {
            values["wxuin"].orEmpty().ifBlank { normalizeLibraryUin(cookie) }
        }
        if (dirId.isBlank() || uin.isBlank()) throw PlatformApiException("QQ 音乐歌单信息不完整")
        val query = linkedMapOf(
            "uin" to uin,
            "dirid" to dirId,
            "new" to "0",
            "dirinfo" to "1",
            "miniportal" to "1",
            "fromDir2Diss" to "1",
            "mobile" to "1",
            "from" to "0",
            "to" to "500",
            "format" to "json",
            "g_tk" to "5381",
        ).asQuery()
        val response = JSONObject(PlatformHttp.get("$PROFILE_DIRECTORY_API?$query", cookie, webHeaders()).text.qqLibraryJson())
        requireSuccess(response, "歌单内容加载失败")
        val tracks = response.optJSONArray("SongList").qqLibraryObjects { item ->
            profileDirectoryTrack(item, hasVipAccess)
        }
        return playlist.copy(
            title = response.optString("Title").ifBlank { playlist.title },
            subtitle = response.optString("NickName").ifBlank { playlist.subtitle },
            description = response.optString("Desc").ifBlank { playlist.description },
            count = response.optInt("SongCount").takeIf { it > 0 }
                ?: response.optInt("TotalSongNum").takeIf { it > 0 }
                ?: tracks.size,
            tracks = QqTrackAccessResolver().resolve(tracks, cookie),
            artworkUrl = qqPlaylistDetailArtwork(
                playlist.artworkUrl,
                response.qqCoverUrl(),
                tracks.isNotEmpty(),
            ),
        )
    }

    private fun createdPlaylist(item: JSONObject, userId: String): MusicPlaylist {
        val publicId = item.optString("dissid").trim().takeUnless { it.isBlank() || it == "0" }
            ?: item.optString("tid").trim().takeUnless { it.isBlank() || it == "0" }
        val dirId = item.optString("dirid").trim().takeUnless { it.isBlank() || it == "0" }
        val remoteId = publicId ?: dirId?.let { "$PROFILE_DIRECTORY_PREFIX$it" }
            ?: throw IllegalArgumentException("无效歌单")
        val title = item.optString("diss_name").ifBlank { item.optString("title") }.trim()
        if (title.isBlank()) throw IllegalArgumentException("无效歌单")
        val count = listOf("song_count", "song_num", "song_cnt")
            .firstNotNullOfOrNull { key -> item.optInt(key).takeIf { it > 0 } } ?: 0
        return playlist(
            remoteId,
            title,
            userId,
            item.optString("diss_desc").ifBlank { item.optString("desc") },
            count,
            item.qqCoverUrl().orEmpty(),
        ).copy(qqDirectoryId = dirId?.toLongOrNull())
    }

    private fun legacyCreatedPlaylist(item: JSONObject, userId: String): MusicPlaylist {
        val id = item.optString("dissid").trim()
        val title = item.optString("dissname").trim()
        if (id.isBlank() || title.isBlank()) throw IllegalArgumentException("无效歌单")
        return playlist(
            id,
            title,
            userId,
            item.optString("introduction"),
            item.optInt("song_count").takeIf { it > 0 } ?: item.optInt("song_num"),
            item.qqCoverUrl().orEmpty(),
        ).copy(qqDirectoryId = item.optLong("dirid").takeIf { it > 0 })
    }

    private fun playlist(
        remoteId: String,
        title: String,
        subtitle: String,
        description: String,
        count: Int,
        cover: String,
    ): MusicPlaylist {
        val colors = qqArtworkColors(title)
        return MusicPlaylist(
            id = "qq-$remoteId",
            source = MusicSource.QQ,
            title = title,
            subtitle = subtitle,
            description = description.ifBlank { "QQ 音乐歌单" },
            count = count,
            artworkStart = colors.first,
            artworkEnd = colors.second,
            artworkMark = title.take(1),
            tracks = emptyList(),
            artworkUrl = normalizeCover(cover),
        )
    }

    private fun profileDirectoryTrack(item: JSONObject, hasVipAccess: Boolean): MusicTrack {
        val packed = item.optString("data")
        if (packed.isBlank()) return item.toQqTrack(hasVipAccess)
        val parts = packed.split('|')
        fun value(index: Int) = parts.getOrNull(index).orEmpty().trim()
        val mid = value(0)
        if (mid.isBlank()) throw IllegalArgumentException("无效歌曲")
        val albumMid = value(4)
        val title = value(1).ifBlank { "未知歌曲" }
        val colors = qqArtworkColors(title)
        return MusicTrack(
            id = "qq-$mid",
            source = MusicSource.QQ,
            title = title,
            artists = value(3).ifBlank { "未知歌手" },
            album = value(5),
            durationMs = value(7).toLongOrNull()?.times(1_000L) ?: 0L,
            artworkStart = colors.first,
            artworkEnd = colors.second,
            artworkMark = value(1).take(1),
            previewUrl = "",
            artworkUrl = albumMid.takeIf(String::isNotBlank)?.let {
                "https://y.gtimg.cn/music/photo_new/T002R500x500M000$it.jpg"
            },
            mediaId = mid,
        )
    }

    private fun requireLogin(cookie: String, userId: String) {
        if (cookie.isBlank() || userId.isBlank()) throw PlatformApiException("请先登录 QQ 音乐", 301)
    }

    private fun requireSuccess(response: JSONObject, message: String) {
        val code = response.opt("code")?.toString()?.trim().orEmpty()
        if (code.isNotBlank() && code != "0") throw PlatformApiException(message)
    }

    private fun profileAssetQuery(userId: String, reqType: String, offset: Int, limit: Int) = linkedMapOf(
        "format" to "json",
        "inCharset" to "utf8",
        "outCharset" to "utf-8",
        "platform" to "yqq.json",
        "needNewCode" to "0",
        "loginUin" to userId,
        "hostUin" to "0",
        "notice" to "0",
        "g_tk" to "5381",
        "ct" to "20",
        "cid" to "205360956",
        "userid" to userId,
        "reqtype" to reqType,
        "sin" to offset.toString(),
        "ein" to (offset + limit - 1).toString(),
    ).asQuery()

    private companion object {
        const val FAVORITES_ID = "qq-profile:favorites"
        const val PROFILE_DIRECTORY_PREFIX = "profile:dir:"
        const val CREATED_PLAYLIST_API = "https://c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss"
        const val PROFILE_ASSET_API = "https://c.y.qq.com/fav/fcgi-bin/fcg_get_profile_order_asset.fcg"
        const val PROFILE_DIRECTORY_API = "https://s.plcloud.music.qq.com/fcgi-bin/fcg_musiclist_getinfo.fcg"
    }
}

internal const val QQ_FAVORITES_PLAYLIST_ID = "qq-profile:favorites"
internal const val QQ_PROFILE_DIRECTORY_ID_PREFIX = "qq-profile:dir:"

internal fun MusicPlaylist.isQqFavoritesShortcut(): Boolean {
    val normalizedTitle = title.filterNot(Char::isWhitespace)
    return id == QQ_FAVORITES_PLAYLIST_ID || normalizedTitle == "我喜欢" || normalizedTitle == "我喜欢的音乐"
}

internal fun MusicPlaylist.isQqHiddenCreatedPlaylist(): Boolean {
    if (isQqFavoritesShortcut()) return true
    val normalizedTitle = title.filterNot(Char::isWhitespace).lowercase()
    return normalizedTitle == "qzone背景音乐" || normalizedTitle == "本地上传"
}

private fun Map<String, String>.asQuery(): String = entries.joinToString("&") { (key, value) ->
    "${key.urlEncoded()}=${value.urlEncoded()}"
}

private fun webHeaders(): Map<String, String> = mapOf(
    "Referer" to "https://y.qq.com/",
    "Origin" to "https://y.qq.com",
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
)

private fun String.qqLibraryJson(): String {
    val start = indexOf('{')
    val end = lastIndexOf('}')
    return if (start >= 0 && end >= start) substring(start, end + 1) else this
}

private fun normalizeCover(value: String): String? = value.trim().takeIf(String::isNotBlank)?.let {
    if (it.startsWith("//")) "https:$it" else it.replaceFirst("http://", "https://")
}

private fun normalizeLibraryUin(cookie: String): String = listOf(
    "wxuin", "uin", "ptui_loginuin", "luin", "pt2gguin", "superuin", "p_uin", "musicid", "userid",
).firstNotNullOfOrNull { cookie.cookieValues()[it]?.takeIf(String::isNotBlank) }
    .orEmpty().removePrefix("o").trimStart('0').trim()

private fun <T> JSONArray?.qqLibraryObjects(transform: (JSONObject) -> T): List<T> = buildList {
    val values = this@qqLibraryObjects ?: return@buildList
    for (index in 0 until values.length()) values.optJSONObject(index)?.let { item ->
        runCatching { transform(item) }.getOrNull()?.let(::add)
    }
}
