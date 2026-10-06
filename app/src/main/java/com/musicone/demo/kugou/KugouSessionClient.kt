package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject

internal data class KugouQrCode(
    val key: String,
    val url: String,
    val deviceCookie: String,
)

internal data class KugouQrCheckResult(
    val status: PlatformQrLoginStatus,
    val message: String,
    val cookie: String,
    val account: MusicAccount? = null,
)

internal data class KugouLibrary(
    val favorite: MusicPlaylist?,
    val created: List<MusicPlaylist>,
    val collected: List<MusicPlaylist>,
)

internal class KugouSessionClient {
    fun createQrCode(): KugouQrCode {
        val deviceCookie = KugouProtocol.createDeviceCookie()
        val params = mapOf(
            "appid" to "1001", "type" to "1", "plat" to "4",
            "qrcode_txt" to "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=${KugouProtocol.appId}&",
            "srcappid" to "2919",
        )
        val response = JSONObject(loginGet("https://login-user.kugou.com/v2/qrcode", params, deviceCookie).text)
        val key = response.optJSONObject("data")?.optString("qrcode").orEmpty()
        if (key.isBlank()) throw PlatformApiException(response.optString("error").ifBlank { "酷狗音乐二维码生成失败" })
        return KugouQrCode(
            key,
            "https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=${key.urlEncoded()}",
            deviceCookie,
        )
    }

    fun checkQrCode(key: String, deviceCookie: String): KugouQrCheckResult {
        val response = JSONObject(loginGet(
            "https://login-user.kugou.com/v2/get_userinfo_qrcode",
            mapOf("plat" to "4", "appid" to KugouProtocol.appId, "srcappid" to "2919", "qrcode" to key),
            deviceCookie,
        ).text)
        val data = response.optJSONObject("data") ?: JSONObject()
        val status = when (data.optInt("status", Int.MIN_VALUE)) {
            4 -> PlatformQrLoginStatus.SUCCESS
            2, 3 -> PlatformQrLoginStatus.WAITING_CONFIRM
            -1, 5, 6 -> PlatformQrLoginStatus.EXPIRED
            0, 1 -> PlatformQrLoginStatus.WAITING_SCAN
            else -> PlatformQrLoginStatus.FAILED
        }
        if (status != PlatformQrLoginStatus.SUCCESS) {
            val hint = when (status) {
                PlatformQrLoginStatus.WAITING_SCAN -> "请使用酷狗音乐扫码"
                PlatformQrLoginStatus.WAITING_CONFIRM -> "已扫码，请在手机上确认"
                PlatformQrLoginStatus.EXPIRED -> "二维码已过期，请刷新"
                else -> response.optString("error").ifBlank { "酷狗音乐登录失败" }
            }
            return KugouQrCheckResult(status, hint, deviceCookie)
        }
        val token = data.optString("token")
        val userId = data.opt("userid")?.toString().orEmpty()
        if (token.isBlank() || userId.isBlank() || userId == "0") {
            return KugouQrCheckResult(PlatformQrLoginStatus.FAILED, "酷狗音乐没有返回登录凭据", deviceCookie)
        }
        val cookie = deviceCookie.cookieValues().toMutableMap().apply {
            put("token", token)
            put("userid", userId)
            data.kugouProfileNickname()?.let { put("nickname", it) }
            data.kugouProfileAvatar()?.let { put("avatar", it) }
        }.asCookieHeader()
        return KugouQrCheckResult(
            PlatformQrLoginStatus.SUCCESS,
            "登录成功",
            cookie,
            MusicAccount(
                MusicSource.KUGOU,
                userId,
                data.kugouProfileNickname() ?: "酷狗用户 $userId",
                data.kugouProfileAvatar(),
                hasVipAccess = data.kugouHasVipAccess(),
            ),
        )
    }

    fun account(cookie: String): MusicAccount {
        val values = cookie.cookieValues()
        val userId = values["userid"].orEmpty().ifBlank { values["KugooID"].orEmpty() }
        val token = values["token"].orEmpty().ifBlank { values["t"].orEmpty() }
        if (userId.isBlank() || userId == "0" || token.isBlank()) {
            throw PlatformApiException("酷狗音乐登录状态已失效", 301)
        }
        return MusicAccount(
            MusicSource.KUGOU,
            userId,
            values["nickname"].orEmpty().ifBlank { "酷狗用户 $userId" },
            values["avatar"]?.takeIf(String::isNotBlank),
            // App 登录凭据无法通过旧网页会员接口查询；具体版权与音质仍由换票结果校验。
            hasVipAccess = true,
        )
    }

    fun likedTrackIds(cookie: String): Set<String> =
        favoriteEntries(cookie).mapTo(linkedSetOf()) { "kugou-${it.hash}" }

    fun setTrackLiked(cookie: String, track: MusicTrack, liked: Boolean) {
        val session = cookie.requiredKugouSession()
        val listId = favoriteListId(session)
        if (liked) addFavorite(session, listId, track) else deleteFavorite(session, listId, track)
    }

    fun library(cookie: String): KugouLibrary {
        val session = cookie.requiredKugouSession()
        val body = JSONObject()
            .put("userid", session.userId).put("token", session.token).put("total_ver", 979)
            .put("type", 2).put("page", 1).put("pagesize", 100)
        val response = androidPost(
            "https://gateway.kugou.com/v7/get_all_list",
            session,
            body,
            extraParams = mapOf("plat" to "1"),
        )
        response.requireKugouSuccess("酷狗音乐歌单加载失败")
        val values = response.optJSONObject("data")?.optJSONArray("info") ?: JSONArray()
        val playlists = buildList {
            for (index in 0 until values.length()) values.optJSONObject(index)?.let { item ->
                item.toKugouCloudPlaylist(session.userId)?.let(::add)
            }
        }
        val favorite = playlists.firstOrNull { it.isFavorite } ?: playlists.firstOrNull {
            it.playlist.title.contains("喜欢") && it.owned
        }
        return KugouLibrary(
            favorite = favorite?.playlist,
            created = playlists.filter { it.owned && !it.isFavorite && !it.isSystem }.map { it.playlist },
            collected = playlists.filterNot { it.owned }.map { it.playlist },
        )
    }

    fun cloudPlaylistDetail(playlist: MusicPlaylist, cookie: String, hasVipAccess: Boolean): MusicPlaylist {
        val session = cookie.requiredKugouSession()
        val listId = playlist.remoteId().removePrefix("cloudlist:")
        if (listId.isBlank()) throw PlatformApiException("酷狗音乐歌单标识无效")
        val body = JSONObject()
            .put("listid", listId).put("userid", session.userId).put("area_code", 1)
            .put("show_relate_goods", 1).put("pagesize", 300).put("allplatform", 1)
            .put("show_cover", 1).put("type", 0).put("token", session.token).put("page", 1)
        val response = androidPost("https://gateway.kugou.com/v4/get_list_all_file", session, body)
        response.requireKugouSuccess("酷狗音乐歌单歌曲加载失败")
        val tracks = response.parseKugouPlaylistTracks(hasVipAccess)
        return playlist.copy(tracks = tracks, count = tracks.size.takeIf { it > 0 } ?: playlist.count)
    }

    private fun addFavorite(session: KugouSession, listId: String, track: MusicTrack) {
        val body = JSONObject()
            .put("userid", session.userId).put("token", session.token).put("listid", listId)
            .put("list_ver", 0).put("type", 0).put("slow_upload", 1).put("scene", "false;null")
            .put("data", JSONArray().put(JSONObject()
                .put("number", 1).put("name", "${track.artists} - ${track.title}")
                .put("hash", track.kugouHashFor(AudioQuality.STANDARD) ?: track.remoteId())
                .put("size", 0).put("sort", 0).put("timelen", track.durationMs).put("bitrate", 0)
                .put("album_id", track.mediaId.toLongOrNull() ?: 0L)
                .put("mixsongid", track.catalogId.toLongOrNull() ?: 0L)))
        androidPost(
            "https://gateway.kugou.com/cloudlist.service/v6/add_song",
            session,
            body,
            extraParams = mapOf(
                "last_time" to (System.currentTimeMillis() / 1_000).toString(),
                "last_area" to "gztx",
            ),
        ).requireKugouSuccess("酷狗音乐我喜欢操作失败")
    }

    private fun deleteFavorite(session: KugouSession, listId: String, track: MusicTrack) {
        val hashes = (track.qualityIds.values + track.remoteId()).map(String::lowercase).toSet()
        val entry = favoriteEntries(session.cookie)
            .firstOrNull { it.hash.lowercase() in hashes } ?: return
        val body = JSONObject()
            .put("listid", listId).put("userid", session.userId).put("token", session.token)
            .put("data", JSONArray().put(JSONObject().put("fileid", entry.fileId)))
            .put("type", 0).put("list_ver", 0)
        androidPost("https://gateway.kugou.com/v4/delete_songs", session, body)
            .requireKugouSuccess("酷狗音乐我喜欢操作失败")
    }

    private fun favoriteEntries(cookie: String): List<KugouFavoriteEntry> {
        val session = cookie.requiredKugouSession()
        val body = JSONObject()
            .put("listid", favoriteListId(session)).put("userid", session.userId).put("area_code", 1)
            .put("show_relate_goods", 1).put("pagesize", 300).put("allplatform", 1)
            .put("show_cover", 1).put("type", 0).put("token", session.token).put("page", 1)
        val response = androidPost("https://gateway.kugou.com/v4/get_list_all_file", session, body)
        response.requireKugouSuccess("酷狗音乐我喜欢加载失败")
        val values = response.optJSONObject("data")?.optJSONArray("info") ?: JSONArray()
        return buildList {
            for (index in 0 until values.length()) values.optJSONObject(index)?.let { item ->
                val hash = listOf("FileHash", "hash", "Hash").firstNotNullOfOrNull {
                    item.optString(it).takeIf(String::isNotBlank)
                }.orEmpty()
                val fileId = item.opt("ID")?.toString()?.toLongOrNull()
                if (hash.isNotBlank() && fileId != null) add(KugouFavoriteEntry(hash, fileId))
            }
        }
    }

    private fun favoriteListId(session: KugouSession): String {
        val body = JSONObject()
            .put("userid", session.userId).put("token", session.token).put("total_ver", 979)
            .put("type", 2).put("page", 1).put("pagesize", 100)
        val response = androidPost(
            "https://gateway.kugou.com/v7/get_all_list",
            session,
            body,
            extraParams = mapOf("plat" to "1"),
        )
        response.requireKugouSuccess("酷狗音乐我喜欢加载失败")
        val info = response.optJSONObject("data")?.optJSONArray("info") ?: JSONArray()
        var fallback = ""
        for (index in 0 until info.length()) {
            val item = info.optJSONObject(index) ?: continue
            val listId = item.opt("listid")?.toString().orEmpty()
            val name = item.optString("name").ifBlank { item.optString("specialname") }
            if (fallback.isBlank() && listId.isNotBlank() && listId != "0") fallback = listId
            if (listId.isNotBlank() && (name.contains("喜欢") || item.optInt("is_default", 0) == 1)) return listId
        }
        return fallback.ifBlank { throw PlatformApiException("酷狗音乐没有找到我喜欢歌单") }
    }

    private fun androidPost(
        baseUrl: String,
        session: KugouSession,
        body: JSONObject,
        extraParams: Map<String, String> = emptyMap(),
    ): JSONObject {
        val params = linkedMapOf(
            "dfid" to session.dfid, "mid" to session.mid, "uuid" to "-",
            "appid" to KugouProtocol.appId, "clientver" to KugouProtocol.clientVersion,
            "clienttime" to (System.currentTimeMillis() / 1_000).toString(),
            "token" to session.token, "userid" to session.userId,
        ).apply { putAll(extraParams) }
        val response = PlatformHttp.postJson(
            KugouProtocol.signedAndroidUrl(baseUrl, params, body.toString()),
            body.toString(),
            session.cookie,
            kugouAndroidHeaders(params, "cloudlist.service.kugou.com"),
        )
        return JSONObject(response.text)
    }

    private fun loginGet(
        baseUrl: String,
        values: Map<String, String>,
        cookie: String,
    ): PlatformHttpResponse {
        val cookies = cookie.cookieValues()
        val params = linkedMapOf(
            "dfid" to cookies["dfid"].orEmpty().ifBlank { "-" },
            "mid" to cookies["KUGOU_API_MID"].orEmpty().ifBlank { "-" },
            "uuid" to "-", "appid" to KugouProtocol.appId, "clientver" to KugouProtocol.clientVersion,
            "clienttime" to (System.currentTimeMillis() / 1_000).toString(),
        ).apply { putAll(values) }
        return PlatformHttp.get(
            KugouProtocol.signedSongInfoUrl(baseUrl, params),
            cookie,
            kugouAndroidHeaders(params, null),
        )
    }
}

private data class KugouFavoriteEntry(val hash: String, val fileId: Long)

private data class KugouCloudPlaylist(
    val playlist: MusicPlaylist,
    val owned: Boolean,
    val isFavorite: Boolean,
    val isSystem: Boolean,
)

private fun JSONObject.toKugouCloudPlaylist(userId: String): KugouCloudPlaylist? {
    val listId = opt("listid")?.toString().orEmpty().takeIf { it.isNotBlank() && it != "0" } ?: return null
    val title = optString("name").ifBlank { optString("specialname") }.ifBlank { return null }
    val ownerId = opt("list_create_userid")?.toString().orEmpty()
    val type = optInt("type", 0)
    val isDef = optInt("is_def", 0)
    val owned = ownerId == userId || (ownerId.isBlank() && type == 0)
    val colors = kugouCloudArtworkColors(listId)
    return KugouCloudPlaylist(
        playlist = MusicPlaylist(
            id = "kugou-cloudlist:$listId",
            source = MusicSource.KUGOU,
            title = title,
            subtitle = if (owned) "我创建的歌单" else optString("list_create_username").ifBlank { "已收藏歌单" },
            description = optString("intro"),
            count = opt("count")?.toString()?.toDoubleOrNull()?.toInt() ?: 0,
            artworkStart = colors.first,
            artworkEnd = colors.second,
            artworkMark = title.take(1),
            tracks = emptyList(),
            artworkUrl = optString("pic").kugouCloudArtworkUrl(),
        ),
        owned = owned,
        isFavorite = owned && (isDef == 2 || title.contains("喜欢")),
        isSystem = owned && isDef == 1,
    )
}

private fun String.kugouCloudArtworkUrl(): String? = trim().takeIf(String::isNotBlank)?.let {
    it.replace("{size}", "500").let { url -> if (url.startsWith("//")) "https:$url" else url.replaceFirst("http://", "https://") }
}

private fun kugouCloudArtworkColors(key: String): Pair<Long, Long> {
    val palettes = listOf(
        0xFF57B8F6L to 0xFF1976C9L,
        0xFF65C8D0L to 0xFF277D9AL,
        0xFF84A7F7L to 0xFF4A61B4L,
        0xFF75C9A5L to 0xFF287A69L,
    )
    return palettes[(key.hashCode() and Int.MAX_VALUE) % palettes.size]
}

private fun JSONObject.kugouProfileNickname(): String? =
    listOf("nickname", "username", "nick_name")
        .firstNotNullOfOrNull { name -> optString(name).takeIf(String::isNotBlank) }

private fun JSONObject.kugouProfileAvatar(): String? =
    listOf("pic", "userpic", "avatar", "headimgurl", "img")
        .firstNotNullOfOrNull { name -> optString(name).takeIf(String::isNotBlank) }
        ?.let { if (it.startsWith("//")) "https:$it" else it.replaceFirst("http://", "https://") }

private fun JSONObject.kugouHasVipAccess(): Boolean =
    listOf("vip_type", "vip_user_type", "m_type", "is_vip").any { name ->
        opt(name)?.toString()?.toIntOrNull()?.let { it > 0 } == true
    }
