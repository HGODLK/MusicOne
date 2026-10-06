package com.musicone.demo

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

internal class KugouApiClient {
    private val session = KugouSessionClient()
    private val privilegeCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, JSONObject>>()

    fun createQrCode(): KugouQrCode = session.createQrCode()
    fun checkQrCode(key: String, deviceCookie: String): KugouQrCheckResult = session.checkQrCode(key, deviceCookie)
    fun account(cookie: String): MusicAccount = session.account(cookie)
    fun likedTrackIds(cookie: String): Set<String> = session.likedTrackIds(cookie)
    fun setTrackLiked(cookie: String, track: MusicTrack, liked: Boolean) = session.setTrackLiked(cookie, track, liked)
    fun library(cookie: String): KugouLibrary = session.library(cookie)

    fun search(query: String, cookie: String): List<MusicTrack> {
        if (query.isBlank()) return emptyList()
        val userId = cookie.cookieValues()["userid"].orEmpty().ifBlank { "-1" }
        val url = "$SEARCH_API?keyword=${query.trim().urlEncoded()}&platform=WebFilter&format=json&page=1&pagesize=20&userid=$userId&clientver=&tag=em&filter=2&iscorrection=1&privilege_filter=0&_=${System.currentTimeMillis()}"
        val response = PlatformHttp.get(url, cookie, kugouMobileHeaders())
        return JSONObject(response.text).parseKugouSearchTracks(hasVipAccess(cookie))
    }

    fun searchCollections(query: String, cookie: String): List<MusicPlaylist> {
        if (query.isBlank()) return emptyList()
        val value = query.trim().urlEncoded()
        val headers = kugouMobileHeaders() + ("x-router" to "mobilecdn.kugou.com")
        // 专辑和歌单属于附加结果，任一接口波动时仍保留歌曲搜索及另一个已成功分类。
        val albums = runCatching { JSONObject(PlatformHttp.get(
            "$ALBUM_SEARCH_API?keyword=$value&format=json&page=1&pagesize=10", cookie, headers,
        ).text).parseKugouSearchAlbums() }.getOrDefault(emptyList())
        val playlists = runCatching { JSONObject(PlatformHttp.get(
            "$PLAYLIST_SEARCH_API?keyword=$value&platform=WebFilter&format=json&page=1&pagesize=10&filter=0",
            cookie, headers,
        ).text).parseKugouSearchPlaylists() }.getOrDefault(emptyList())
        return albums + playlists
    }

    fun recommendedPlaylists(cookie: String, page: Int = 0): List<MusicPlaylist> {
        val response = PlatformHttp.get(RECOMMENDED_PLAYLIST_API, cookie, kugouMobileHeaders())
        val playlists = JSONObject(response.text).parseKugouRecommendedPlaylists(hasVipAccess(cookie))
        if (playlists.isEmpty()) throw PlatformApiException("酷狗音乐没有返回推荐歌单")
        return recommendationWindow(playlists, 8, page)
    }

    fun recommendedTracks(cookie: String, page: Int): List<MusicTrack> {
        val playlists = recommendedPlaylists(cookie, 0)
        val playlist = playlists[Math.floorMod(page, playlists.size)]
        return recommendationWindow(playlistDetail(playlist, cookie).tracks, 12, page)
    }

    fun playlistDetail(playlist: MusicPlaylist, cookie: String): MusicPlaylist {
        if (playlist.remoteId().startsWith("cloudlist:")) {
            return session.cloudPlaylistDetail(playlist, cookie, hasVipAccess(cookie))
        }
        if (playlist.remoteId().startsWith("album:")) return albumDetail(playlist, cookie)
        if (hasCompleteKugouTrackList(playlist.tracks.size, playlist.count)) return playlist
        val id = playlist.remoteId()
        val url = "$PLAYLIST_TRACK_API?specialid=${id.urlEncoded()}&page=1&pagesize=300&version=9108&area_code=1"
        val response = JSONObject(PlatformHttp.get(
            url,
            cookie,
            kugouMobileHeaders() + ("x-router" to "mobilecdn.kugou.com"),
        ).text)
        val tracks = response.parseKugouPlaylistTracks(hasVipAccess(cookie))
        if (tracks.isEmpty()) throw PlatformApiException("酷狗音乐没有返回歌单歌曲")
        return playlist.copy(tracks = tracks, count = tracks.size)
    }

    private fun albumDetail(album: MusicPlaylist, cookie: String): MusicPlaylist {
        val id = album.remoteId().removePrefix("album:")
        val response = JSONObject(PlatformHttp.get(
            "$ALBUM_TRACK_API?albumid=${id.urlEncoded()}&page=1&pagesize=300&version=9108&area_code=1",
            cookie,
            kugouMobileHeaders() + ("x-router" to "mobilecdn.kugou.com"),
        ).text)
        val tracks = response.parseKugouPlaylistTracks(hasVipAccess(cookie))
        if (tracks.isEmpty()) throw PlatformApiException("酷狗音乐没有返回专辑歌曲")
        return album.copy(tracks = tracks, count = tracks.size)
    }

    fun playbackSource(
        track: MusicTrack,
        cookie: String,
        quality: AudioQuality,
        inspectMedia: Boolean = false,
    ): PlaybackSource {
        val hash = track.kugouHashFor(quality)
            ?: throw PlatformApiException("这首歌曲没有${quality.label}资源")
        runCatching { playbackV6(track, hash, cookie, quality, inspectMedia) }.getOrNull()?.let { return it }
        runCatching { playbackV5(track, hash, cookie, quality, inspectMedia) }.getOrNull()?.let { return it }
        val source = publicPlayback(track, hash, cookie, quality, track.accessBadge != null)
        if (source.actualQuality != quality) {
            throw PlatformApiException("酷狗音乐未返回${quality.displayLabel(MusicSource.KUGOU)}音源")
        }
        if (inspectMedia && KugouAudioValidation.verify(source.url, track, source.actualQuality) == null) {
            throw PlatformApiException("未获取到可验证的完整${quality.displayLabel(MusicSource.KUGOU)}音源")
        }
        return source.copy(verificationPending = !inspectMedia)
    }

    fun lyrics(track: MusicTrack, cookie: String): List<TimedLyric> {
        val hash = track.kugouHashFor(AudioQuality.STANDARD) ?: track.remoteId()
        val searchUrl = "$LYRIC_SEARCH_API?ver=1&client=mobi&duration=${track.durationMs}&hash=${hash.urlEncoded()}&album_audio_id=${track.catalogId.urlEncoded()}"
        val search = JSONObject(PlatformHttp.get(searchUrl, cookie, kugouMobileHeaders()).text)
        val candidate = search.optJSONArray("candidates")?.optJSONObject(0) ?: return emptyList()
        val id = candidate.opt("id")?.toString().orEmpty()
        val accessKey = candidate.optString("accesskey")
        if (id.isBlank() || accessKey.isBlank()) return emptyList()
        val baseUrl = "$LYRIC_DOWNLOAD_API?ver=1&client=pc&id=${id.urlEncoded()}&accesskey=${accessKey.urlEncoded()}"
        val krcContent = JSONObject(PlatformHttp.get("$baseUrl&fmt=krc&charset=utf8", cookie, kugouMobileHeaders()).text)
            .optString("content")
        parseKugouKrcBase64(krcContent).takeIf(List<TimedLyric>::isNotEmpty)?.let { return it }
        val lrcContent = JSONObject(PlatformHttp.get("$baseUrl&fmt=lrc&charset=utf8", cookie, kugouMobileHeaders()).text)
            .optString("content")
        if (lrcContent.isBlank()) return emptyList()
        val lrc = runCatching { String(Base64.decode(lrcContent, Base64.DEFAULT), Charsets.UTF_8) }
            .getOrDefault(lrcContent)
        return parseLrc(lrc)
    }

    private fun playbackV6(
        track: MusicTrack,
        hash: String,
        cookie: String,
        quality: AudioQuality,
        inspectMedia: Boolean,
    ): PlaybackSource {
        val login = cookie.requiredKugouSession()
        // v6 接口以标准档 hash 作为资源主键，并在 relate_goods 中返回可用的其他档位。
        val lowerHash = (track.kugouHashFor(AudioQuality.STANDARD) ?: hash).lowercase()
        val key = KugouProtocol.md5(lowerHash + KugouProtocol.liteKey + KugouProtocol.appId + login.mid + login.userId)
        val body = JSONObject()
            .put("area_code", "1")
            .put("behavior", "play")
            .put("qualities", JSONArray(KUGOU_PRIVILEGE_QUALITIES))
            .put("resource", JSONObject()
                .put("album_audio_id", track.catalogId.ifBlank { "0" })
                .put("collect_list_id", "3")
                .put("collect_time", System.currentTimeMillis())
                .put("hash", lowerHash)
                .put("id", 0)
                .put("page_id", 1)
                .put("type", "audio"))
            .put("token", login.token)
            .put("tracker_param", JSONObject()
                .put("all_m", 1).put("auth", "").put("is_free_part", 0).put("key", key)
                .put("module_id", 0).put("need_climax", 1).put("need_xcdn", 1).put("open_time", "")
                .put("pid", "411").put("pidversion", "3001").put("priv_vip_type", "6").put("viptoken", ""))
            .put("userid", login.userId)
            .put("vip", "6")
        val response = cachedPrivilegeResponse(track, login, body)
        val item = response.kugouPrivilegeItem(quality.kugouQuality())
            ?: throw PlatformApiException("当前账号无法播放${quality.label}")
        val info = item.optJSONObject("info") ?: JSONObject()
        val playUrl = info.pickKugouUrl().ifBlank { item.pickKugouUrl() }
        if (playUrl.isBlank()) throw PlatformApiException("当前账号无法播放${quality.label}")
        val responseHash = item.optString("hash")
        val bitRate = normalizedKugouBitrate(info.optInt("bitrate", item.optInt("bitrate", 0)))
        val format = info.optString("extname").ifBlank { item.optString("extname") }
            .ifBlank { playUrl.substringBefore('?').substringAfterLast('.', "audio") }
        val actualQuality = kugouPlaybackQuality(responseHash, playUrl, format, bitRate, track)
            ?: item.optInt("level").kugouQualityFromLevel()
            ?: throw PlatformApiException("酷狗音乐返回了无法识别的音源")
        if (actualQuality != quality) {
            throw PlatformApiException("酷狗音乐未返回${quality.displayLabel(MusicSource.KUGOU)}音源")
        }
        val media = if (inspectMedia) KugouAudioValidation.verify(playUrl, track, quality) else null
        if (inspectMedia && media == null) {
            throw PlatformApiException("未获取到可验证的完整${quality.displayLabel(MusicSource.KUGOU)}音源")
        }
        val trial = item.optInt("is_free_part", 0) == 1 || item.optInt("fail_process", 0) == 4
        return PlaybackSource(
            url = playUrl,
            requestedQuality = quality,
            actualQuality = actualQuality,
            bitRate = bitRate.takeIf { it > 0 } ?: quality.bitRate,
            format = format,
            trial = trial,
            actualFormat = media?.let(::kugouMediaDescription),
            verificationPending = !inspectMedia,
        )
    }

    private fun cachedPrivilegeResponse(
        track: MusicTrack,
        login: KugouSession,
        body: JSONObject,
    ): JSONObject {
        val cacheKey = "${track.id}:${login.userId}:${login.token.hashCode()}"
        privilegeCache[cacheKey]?.takeIf { System.currentTimeMillis() - it.first < 30_000L }?.second?.let { return it }
        // 音质菜单会并发探测多档，首个请求负责换票，其余档位共享同一份 relate_goods。
        return synchronized(privilegeCache) {
            privilegeCache[cacheKey]?.takeIf { System.currentTimeMillis() - it.first < 30_000L }
                ?.second?.let { return@synchronized it }
            val clientTime = (System.currentTimeMillis() / 1_000).toString()
            val params = linkedMapOf(
                "dfid" to login.dfid, "mid" to login.mid, "uuid" to "-",
                "appid" to KugouProtocol.appId, "clientver" to KugouProtocol.clientVersion,
                "clienttime" to clientTime, "token" to login.token, "userid" to login.userId,
            )
            val response = JSONObject(PlatformHttp.postJson(
                KugouProtocol.signedAndroidUrl(PRIVILEGE_PLAYBACK_API, params, body.toString()),
                body.toString(),
                login.cookie,
                kugouAndroidHeaders(params, "tracker.kugou.com"),
            ).text)
            if (response.optInt("status", 0) != 1 || response.optInt("error_code", 0) != 0) {
                throw PlatformApiException(response.optString("message").ifBlank { "酷狗音乐音源换票失败" })
            }
            privilegeCache[cacheKey] = System.currentTimeMillis() to response
            response
        }
    }

    private fun hasVipAccess(cookie: String): Boolean {
        if (cookie.isBlank()) return false
        val validSession = runCatching { cookie.requiredKugouSession() }.isSuccess
        if (validSession) return true
        return runCatching {
            val response = JSONObject(PlatformHttp.get(VIP_INFO_API, cookie, kugouPcHeaders()).text)
            response.optInt("errno", -1) == 0 && response.optInt("error_code", -1) == 0 &&
                response.optInt("vipRemains", 0) > 0 && response.optInt("isExpiredMember", 1) == 0 &&
                response.optInt("role", 0) != 0
        }.getOrDefault(false)
    }

    private fun playbackV5(
        track: MusicTrack,
        hash: String,
        cookie: String,
        quality: AudioQuality,
        inspectMedia: Boolean,
    ): PlaybackSource {
        val login = cookie.requiredKugouSession()
        val clientTime = (System.currentTimeMillis() / 1_000).toString()
        val params = linkedMapOf(
            "dfid" to login.dfid, "mid" to login.mid, "uuid" to "-",
            "appid" to KugouProtocol.appId, "clientver" to "11430",
            "clienttime" to clientTime, "token" to login.token, "userid" to login.userId,
            "album_id" to track.mediaId.ifBlank { "0" }, "area_code" to "1", "hash" to hash.lowercase(),
            "ssa_flag" to "is_fromtrack", "version" to "11430", "page_id" to "967177915",
            "quality" to quality.kugouQuality(), "album_audio_id" to track.catalogId.ifBlank { "0" },
            "behavior" to "play", "pid" to "411", "cmd" to "26", "pidversion" to "3001",
            "IsFreePart" to if (track.trialAvailable) "1" else "0",
            "ppage_id" to "356753938,823673182,967485191",
            "cdnBackup" to "1", "module" to "",
        )
        params["key"] = KugouProtocol.md5(
            hash.lowercase() + KugouProtocol.liteKey + KugouProtocol.appId + login.mid + login.userId,
        )
        val url = KugouProtocol.signedAndroidUrl("https://gateway.kugou.com/v5/url", params, "")
        val response = JSONObject(PlatformHttp.get(
            url,
            cookie,
            kugouAndroidHeaders(params, "trackercdn.kugou.com"),
        ).text)
        val playUrl = response.pickKugouUrl()
        if (playUrl.isBlank()) throw PlatformApiException("当前账号无法播放${quality.label}")
        val data = response.optJSONObject("data") ?: response
        val bitRate = normalizedKugouBitrate(data.optInt("bitRate", data.optInt("bitrate", 0)))
        val format = data.optString("fileType").ifBlank { data.optString("extName") }
            .ifBlank { playUrl.substringBefore('?').substringAfterLast('.', "audio") }
        val responseHash = listOf("hash", "fileHash", "file_hash", "FileHash")
            .firstNotNullOfOrNull { name -> data.optString(name).takeIf(String::isNotBlank) }.orEmpty()
        val actualQuality = kugouPlaybackQuality(responseHash, playUrl, format, bitRate, track)
            ?: throw PlatformApiException("酷狗音乐返回了无法识别的音源")
        if (actualQuality != quality) {
            throw PlatformApiException("酷狗音乐未返回${quality.displayLabel(MusicSource.KUGOU)}音源")
        }
        val trial = data.optInt("is_free_part", response.optInt("is_free_part", 0)) == 1
        val media = if (inspectMedia) KugouAudioValidation.verify(playUrl, track, quality) else null
        if (inspectMedia && media == null) {
            throw PlatformApiException("未获取到可验证的完整${quality.displayLabel(MusicSource.KUGOU)}音源")
        }
        return PlaybackSource(
            playUrl,
            quality,
            actualQuality,
            bitRate.takeIf { it > 0 } ?: quality.bitRate,
            format,
            trial,
            actualFormat = media?.let(::kugouMediaDescription),
            verificationPending = !inspectMedia,
        )
    }

    private fun publicPlayback(
        track: MusicTrack,
        hash: String,
        cookie: String,
        quality: AudioQuality,
        restricted: Boolean,
    ): PlaybackSource {
        val response = JSONObject(PlatformHttp.get(
            "$PUBLIC_PLAYBACK_API?cmd=playInfo&hash=${hash.urlEncoded()}",
            cookie,
            kugouMobileHeaders(),
        ).text)
        val url = response.pickKugouUrl()
        if (url.isBlank() || response.optInt("errcode", 0) != 0) {
            return trackerPlayback(track, hash, cookie, quality, restricted)
        }
        val bitRate = normalizedKugouBitrate(response.optInt("bitRate", quality.bitRate))
        val format = response.optString("extName").ifBlank { url.substringBefore('?').substringAfterLast('.', "audio") }
        val actualQuality = kugouPlaybackQuality(response.optString("hash"), url, format, bitRate, track)
            ?: throw PlatformApiException("酷狗音乐返回了无法识别的音源")
        return PlaybackSource(
            url,
            quality,
            actualQuality,
            bitRate,
            format,
            restricted || response.optInt("is_free_part", 0) == 1,
        )
    }

    private fun trackerPlayback(
        track: MusicTrack,
        hash: String,
        cookie: String,
        quality: AudioQuality,
        restricted: Boolean,
    ): PlaybackSource {
        val lower = hash.lowercase()
        val url = "https://trackercdn.kugou.com/i/v2/?cdnBackup=1&behavior=play&pid=1&cmd=21&appid=1001&hash=$lower&key=${KugouProtocol.md5(lower + "kgcloudv2")}"
        val response = JSONObject(PlatformHttp.get(url, cookie, kugouPcHeaders()).text)
        val playUrl = response.pickKugouUrl()
        if (playUrl.isBlank() || response.optInt("errcode", 0) != 0) {
            throw PlatformApiException("歌曲暂无可用播放地址")
        }
        val bitRate = normalizedKugouBitrate(response.optInt("bitRate", quality.bitRate))
        val format = response.optString("extName").ifBlank { playUrl.substringBefore('?').substringAfterLast('.', "audio") }
        val actualQuality = kugouPlaybackQuality(response.optString("hash"), playUrl, format, bitRate, track)
            ?: throw PlatformApiException("酷狗音乐返回了无法识别的音源")
        return PlaybackSource(
            playUrl,
            quality,
            actualQuality,
            bitRate,
            format,
            restricted,
        )
    }

    companion object {
        private const val SEARCH_API = "https://songsearch.kugou.com/song_search_v2"
        private const val ALBUM_SEARCH_API = "https://gateway.kugou.com/api/v3/search/album"
        private const val PLAYLIST_SEARCH_API = "https://gateway.kugou.com/api/v3/search/special"
        private const val ALBUM_TRACK_API = "https://gateway.kugou.com/api/v3/album/song"
        private const val RECOMMENDED_PLAYLIST_API = "https://m.kugou.com/plist/index&json=true"
        private const val PLAYLIST_TRACK_API = "https://gateway.kugou.com/api/v3/special/song"
        private const val PUBLIC_PLAYBACK_API = "https://m.kugou.com/app/i/getSongInfo.php"
        private const val PRIVILEGE_PLAYBACK_API = "https://gateway.kugou.com/v6/priv_url"
        private const val VIP_INFO_API = "https://vip.kugou.com/recharge/roleinfo"
        private const val LYRIC_SEARCH_API = "https://krcs.kugou.com/search"
        private const val LYRIC_DOWNLOAD_API = "https://lyrics.kugou.com/download"
        private val KUGOU_PRIVILEGE_QUALITIES = listOf(
            "128", "320", "flac", "high", "multitrack", "viper_atmos", "viper_tape", "viper_clear", "super",
        )
    }
}

private fun JSONObject.kugouPrivilegeItem(quality: String): JSONObject? {
    val data = optJSONObject("data") ?: this
    val candidates = buildList {
        add(data)
        data.optJSONArray("relate_goods")?.let { goods ->
            for (index in 0 until goods.length()) goods.optJSONObject(index)?.let(::add)
        }
    }
    return candidates.firstOrNull { item ->
        item.optString("quality").equals(quality, ignoreCase = true) &&
            item.optInt("status", 1) == 1 && item.optInt("_errno", 0) == 0
    }
}

private fun Int.kugouQualityFromLevel(): AudioQuality? = when (this) {
    2 -> AudioQuality.STANDARD
    4 -> AudioQuality.EXHIGH
    5 -> AudioQuality.LOSSLESS
    6, 7 -> AudioQuality.HI_RES
    else -> null
}

internal fun normalizedKugouBitrate(value: Int): Int = if (value in 1..9_999) value * 1_000 else value

private fun kugouMediaDescription(info: KugouMediaInfo): String = when (info.mime) {
    "audio/flac" -> "FLAC · ${info.bitDepth}bit / ${java.math.BigDecimal(info.sampleRate)
        .divide(java.math.BigDecimal(1000)).stripTrailingZeros().toPlainString()}kHz"
    "audio/ogg" -> "OGG"
    else -> "MP3"
}
