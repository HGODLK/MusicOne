package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject

internal class KugouApiClient {
    private val session = KugouSessionClient()
    private val lyricsClient = KugouLyricsClient()
    private val playbackResources = KugouPlaybackResourceClient()
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
        val albums = runCatchingCancellable { JSONObject(PlatformHttp.get(
            "$ALBUM_SEARCH_API?keyword=$value&format=json&page=1&pagesize=10", cookie, headers,
        ).text).parseKugouSearchAlbums() }.getOrDefault(emptyList())
        val playlists = runCatchingCancellable { JSONObject(PlatformHttp.get(
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

    fun playlistDetail(playlist: MusicPlaylist, cookie: String): MusicPlaylist =
        collectPlaylistTrackPages { playlistPage(playlist, cookie, it) }

    fun playlistPage(playlist: MusicPlaylist, cookie: String, cursor: PlaylistTrackCursor?): PlaylistTrackPage {
        if (playlist.remoteId().startsWith("cloudlist:")) {
            return PlaylistTrackPage(session.cloudPlaylistDetail(playlist, cookie, hasVipAccess(cookie),
                newestFirst = playlist.isAccountFavorites()))
        }
        if (hasCompleteKugouTrackList(playlist.tracks.size, playlist.count) && cursor == null) {
            return PlaylistTrackPage(playlist)
        }
        return loadKugouPlaylistTrackPage(playlist, cookie, hasVipAccess(cookie), cursor)
    }

    fun playbackSource(
        track: MusicTrack,
        cookie: String,
        quality: AudioQuality,
        inspectMedia: Boolean = false,
    ): PlaybackSource {
        val firstFailure = try {
            return playbackSourceAttempt(track, cookie, quality, inspectMedia)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            kugouPlaybackFailure("initial", quality, error)
            if (error.stopsPlaybackFallback() || error is java.io.IOException) throw error
            error
        }
        if (runCatching { cookie.requiredKugouSession() }.isFailure) throw firstFailure
        val enriched = try { playbackResources.enrich(track, cookie, quality) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            kugouPlaybackFailure("resource", quality, error)
            if (error.stopsPlaybackFallback() || error is java.io.IOException) throw error
            throw firstFailure
        }
        if (enriched.qualityIds == track.qualityIds && enriched.catalogId == track.catalogId && enriched.mediaId == track.mediaId) {
            throw firstFailure
        }
        return try { playbackSourceAttempt(enriched, cookie, quality, inspectMedia) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            kugouPlaybackFailure("resource_retry", quality, error)
            throw error
        }
    }

    private fun playbackSourceAttempt(
        track: MusicTrack,
        cookie: String,
        quality: AudioQuality,
        inspectMedia: Boolean,
    ): PlaybackSource {
        val hash = track.kugouHashFor(quality) ?: track.qualityIds.values.firstOrNull() ?: track.remoteId()
        if (hash.isBlank()) throw PlatformApiException("歌曲音源信息不完整")
        var accountFailure: Exception? = null
        if (runCatching { cookie.requiredKugouSession() }.isSuccess) {
            val attempts = buildList<Pair<String, () -> PlaybackSource>> {
                add("v5" to { playbackV5(track, hash, cookie, quality, inspectMedia) })
                add("v6" to { playbackV6(track, hash, cookie, quality, inspectMedia) })
            }
            for ((stage, attempt) in attempts) {
                try { return attempt().withKugouMediaHeaders(cookie) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) {
                    kugouPlaybackFailure(stage, quality, error)
                    if (error.stopsPlaybackFallback()) throw error
                    if (error !is TrackUnavailableException) {
                        accountFailure = kugouPreferredPlaybackFailure(accountFailure, error)
                    }
                }
            }
        }
        val source = try { publicPlayback(track, hash, cookie, quality, track.accessBadge != null) }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) {
            kugouPlaybackFailure("public", quality, error)
            if (error.stopsPlaybackFallback()) throw error
            throw (accountFailure ?: error)
        }
        if (source.actualQuality != quality) {
            throw KugouPlaybackFailure(KugouPlaybackFailureReason.QUALITY_MISMATCH, "酷狗音乐未返回${quality.displayLabel(MusicSource.KUGOU)}音源")
        }
        if (inspectMedia && KugouAudioValidation.verify(source.url, track, source.actualQuality,
                kugouMediaHeaders(source.url, cookie)) == null) {
            throw KugouPlaybackFailure(KugouPlaybackFailureReason.MEDIA_PROBE, "未获取到可验证的完整${quality.displayLabel(MusicSource.KUGOU)}音源")
        }
        return source.copy(verificationPending = !inspectMedia).withKugouMediaHeaders(cookie)
    }

    fun lyrics(track: MusicTrack, cookie: String): List<TimedLyric> = lyricsClient.lyrics(track, cookie)

    private fun playbackV6(
        track: MusicTrack,
        hash: String,
        cookie: String,
        quality: AudioQuality,
        inspectMedia: Boolean,
    ): PlaybackSource {
        val login = cookie.requiredKugouSession()
        val credentials = cookie.cookieValues()
        // 按当前目标档位换票，不再一次请求所有档位。
        val lowerHash = hash.lowercase()
        val key = KugouProtocol.md5(lowerHash + KugouProtocol.liteKey + KugouProtocol.appId + login.mid + login.userId)
        val body = JSONObject()
            .put("area_code", "1")
            .put("behavior", "play")
            .put("qualities", JSONArray(listOf(quality.kugouQuality())))
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
                .put("pid", "411").put("pidversion", "3001").put("priv_vip_type", "6")
                .put("viptoken", credentials["vip_token"] ?: credentials["viptoken"].orEmpty()))
            .put("userid", login.userId)
            .put("vip", credentials["vip_type"].orEmpty().ifBlank { "0" })
        val response = cachedPrivilegeResponse(track, login, body, quality, lowerHash)
        val item = response.requireKugouPrivilegeItem(quality)
        val info = item.optJSONObject("info") ?: JSONObject()
        val playUrl = info.pickKugouUrl().ifBlank { item.pickKugouUrl() }
        if (playUrl.isBlank()) throw kugouMissingPlaybackUrl(item, "当前账号无法播放${quality.label}")
        val responseHash = item.optString("hash")
        val bitRate = normalizedKugouBitrate(info.optInt("bitrate", item.optInt("bitrate", 0)))
        val format = info.optString("extname").ifBlank { item.optString("extname") }
            .ifBlank { playUrl.substringBefore('?').substringAfterLast('.', "audio") }
        val detectedQuality = kugouPlaybackQuality(responseHash, playUrl, format, bitRate, track)
        val media = if (inspectMedia) KugouAudioValidation.verify(playUrl, track, quality, kugouMediaHeaders(playUrl, cookie)) else null
        val actualQuality = item.optInt("level").kugouQualityFromLevel()
            ?: item.optString("quality").kugouQualityFromName()
            ?: (if (quality == AudioQuality.HI_RES && detectedQuality == AudioQuality.LOSSLESS &&
                media?.bitDepth?.let { it > 16 } == true) AudioQuality.HI_RES else detectedQuality)
            ?: throw PlatformApiException("酷狗音乐返回了无法识别的音源")
        if (actualQuality != quality) {
            throw KugouPlaybackFailure(KugouPlaybackFailureReason.QUALITY_MISMATCH, "酷狗音乐未返回${quality.displayLabel(MusicSource.KUGOU)}音源")
        }
        if (inspectMedia && media == null) {
            throw KugouPlaybackFailure(KugouPlaybackFailureReason.MEDIA_PROBE, "未获取到可验证的完整${quality.displayLabel(MusicSource.KUGOU)}音源")
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
        quality: AudioQuality,
        hash: String,
    ): JSONObject {
        val cacheKey = "${track.id}:${login.userId}:${login.token.hashCode()}:${quality.kugouQuality()}:$hash"
        privilegeCache[cacheKey]?.takeIf { System.currentTimeMillis() - it.first < 30_000L }?.second?.let { return it }
        // 单档结果只能复用于同一账号、资源与目标音质。
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
                throw kugouPlaybackApiError(response, "酷狗音乐音源换票失败")
            }
            android.util.Log.i(
                "KugouPlayback",
                "stage=v6_response qualities=${response.kugouPlayableQualityNames().joinToString(",").ifBlank { "none" }}",
            )
            privilegeCache[cacheKey] = System.currentTimeMillis() to response
            response
        }
    }

    private fun hasVipAccess(cookie: String): Boolean {
        if (cookie.isBlank()) return false
        val validSession = runCatching { cookie.requiredKugouSession() }.isSuccess
        if (validSession) return true
        return runCatchingCancellable {
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
        response.requireKugouPlaybackResponse()
        val item = response.kugouPrivilegeItem(quality.kugouQuality())
            ?: response.optJSONObject("data") ?: response
        val data = item.optJSONObject("info") ?: item
        val playUrl = data.pickKugouUrl().ifBlank { item.pickKugouUrl() }.ifBlank { response.pickKugouUrl() }
        if (playUrl.isBlank()) throw kugouMissingPlaybackUrl(response, "当前账号无法播放${quality.label}")
        val bitRate = normalizedKugouBitrate(data.optInt("bitRate", data.optInt("bitrate",
            item.optInt("bitRate", item.optInt("bitrate", 0)))))
        val format = data.optString("fileType").ifBlank { data.optString("extName") }
            .ifBlank { data.optString("extname") }.ifBlank { item.optString("extname") }
            .ifBlank { playUrl.substringBefore('?').substringAfterLast('.', "audio") }
        val responseHash = listOf("hash", "fileHash", "file_hash", "FileHash")
            .firstNotNullOfOrNull { name -> data.optString(name).takeIf(String::isNotBlank) }.orEmpty()
        val detectedQuality = item.optInt("level").kugouQualityFromLevel()
            ?: item.optString("quality").kugouQualityFromName()
            ?: kugouPlaybackQuality(responseHash, playUrl, format, bitRate, track)
            ?: throw PlatformApiException("酷狗音乐返回了无法识别的音源")
        val media = if (inspectMedia) KugouAudioValidation.verify(playUrl, track, quality, kugouMediaHeaders(playUrl, cookie)) else null
        val actualQuality = if (quality == AudioQuality.HI_RES && detectedQuality == AudioQuality.LOSSLESS &&
            media?.bitDepth?.let { it > 16 } == true) AudioQuality.HI_RES else detectedQuality
        if (actualQuality != quality) {
            throw KugouPlaybackFailure(KugouPlaybackFailureReason.QUALITY_MISMATCH, "酷狗音乐未返回${quality.displayLabel(MusicSource.KUGOU)}音源")
        }
        val trial = data.optInt("is_free_part", response.optInt("is_free_part", 0)) == 1
        if (inspectMedia && media == null) {
            throw KugouPlaybackFailure(KugouPlaybackFailureReason.MEDIA_PROBE, "未获取到可验证的完整${quality.displayLabel(MusicSource.KUGOU)}音源")
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
        if (response.optInt("error_code", response.optInt("errcode", 0)) != 0) {
            val error = kugouPlaybackApiError(response, "酷狗音乐公开取址失败")
            if (error.stopsPlaybackFallback()) throw error
        }
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
        response.requireKugouPlaybackResponse()
        val playUrl = response.pickKugouUrl()
        if (playUrl.isBlank() || response.optInt("errcode", 0) != 0) {
            throw kugouMissingPlaybackUrl(response, "歌曲暂无可用播放地址")
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
        private const val RECOMMENDED_PLAYLIST_API = "https://m.kugou.com/plist/index&json=true"
        private const val PUBLIC_PLAYBACK_API = "https://m.kugou.com/app/i/getSongInfo.php"
        private const val PRIVILEGE_PLAYBACK_API = "https://gateway.kugou.com/v6/priv_url"
        private const val VIP_INFO_API = "https://vip.kugou.com/recharge/roleinfo"
    }
}

internal fun normalizedKugouBitrate(value: Int): Int = if (value in 1..9_999) value * 1_000 else value

private fun kugouMediaDescription(info: KugouMediaInfo): String = when (info.mime) {
    "audio/flac" -> "FLAC · ${info.bitDepth}bit / ${java.math.BigDecimal(info.sampleRate)
        .divide(java.math.BigDecimal(1000)).stripTrailingZeros().toPlainString()}kHz"
    "audio/ogg" -> "OGG"
    else -> "MP3"
}
