package com.musicone.demo

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.URI

internal class QqApiClient {
    private val playbackSources = QqPlaybackSourceCache()
    fun account(cookie: String): MusicAccount {
        val userId = normalizeUin(cookie)
        val musicKey = qqMusicKey(cookie)
        if (userId.isBlank() || musicKey.isBlank()) throw PlatformApiException("QQ 音乐登录状态已失效", 301)
        val query = linkedMapOf(
            "g_tk" to hash33(musicKey, 5381).toString(),
            "format" to "json", "inCharset" to "utf-8", "outCharset" to "utf-8",
            "notice" to "0", "cid" to "205360838", "needNewCode" to "0",
            "loginUin" to userId, "hostUin" to "0", "userid" to userId, "reqfrom" to "1",
        ).entries.joinToString("&") { (key, value) -> "${key.urlEncoded()}=${value.urlEncoded()}" }
        val profile = runCatching {
            JSONObject(PlatformHttp.get("$PROFILE_API?$query", cookie, qqHeaders("https://y.qq.com/")).text.qqJsonText())
        }.getOrNull()
        if (profile?.optInt("code", 0) == 1000) throw PlatformApiException("QQ 音乐登录状态已失效", 301)
        val membership = QqEntitlements.rememberMembership(cookie, QqMembershipClient().query(cookie))
        return qqAccountFromProfile(userId, cookie, profile, membership?.vip == true)
    }

    suspend fun search(query: String, cookie: String, hasVipAccess: Boolean = false): List<MusicTrack> = coroutineScope {
        if (query.isBlank()) return@coroutineScope emptyList()
        val tracks = searchTracks(query, cookie, hasVipAccess)
        val batchResolved = tracks.map { it.withQqArtworkFallback(tracks) }
        val missingArtwork = batchResolved.filter { it.artworkUrl.isNullOrBlank() }
        val targetedArtwork = missingArtwork.map { track ->
            async {
                val candidates = runCatching {
                    searchTracks("${track.title} ${track.artists}", cookie, hasVipAccess)
                }.getOrDefault(emptyList())
                track.id to track.withQqArtworkFallback(candidates)
            }
        }.awaitAll().toMap()
        // 搜索接口会因账号和排序策略漏掉正式发行版；缺图项用同名歌手定向查询补齐。
        QqTrackAccessResolver().resolve(batchResolved.map { targetedArtwork[it.id] ?: it }, cookie)
    }

    /** 信息流 AI 歌单只给出种子曲名称；这里只取标题完全匹配的歌曲供卡片直接播放。 */
    fun searchFeedSeedTrack(query: String, cookie: String, hasVipAccess: Boolean = false): MusicTrack? {
        val normalizedQuery = query.qqComparableSongTitle()
        val candidates = searchTracks(query, cookie, hasVipAccess)
        val exact = candidates.firstOrNull {
            it.title.qqComparableSongTitle() == normalizedQuery
        } ?: candidates.mapNotNull { track ->
            qqArtworkTitleMatchScore(query, track.title)?.let { track to it }
        }.minByOrNull { it.second }?.first
        return exact?.withQqArtworkFallback(candidates)
    }

    /** 只补全缺失封面，不触发换票或音质探测。 */
    fun resolveTrackArtwork(track: MusicTrack, cookie: String, hasVipAccess: Boolean = false): MusicTrack {
        if (!track.artworkUrl.isNullOrBlank()) return track
        val query = listOf(track.title, track.artists.takeUnless { it == "未知歌手" }.orEmpty())
            .filter(String::isNotBlank)
            .joinToString(" ")
        if (query.isBlank()) return track
        return track.withQqArtworkFallback(searchTracks(query, cookie, hasVipAccess))
    }

    private fun searchTracks(query: String, cookie: String, hasVipAccess: Boolean): List<MusicTrack> {
        val url = "$SEARCH_API?w=${query.trim().urlEncoded()}&format=json&p=1&n=20"
        return JSONObject(PlatformHttp.get(url, cookie, qqHeaders(SEARCH_REFERER)).text.qqJsonText())
            .parseQqSearchTracks(hasVipAccess)
    }

    fun radioTracks(
        cookie: String,
        count: Int = 20,
        hasVipAccess: Boolean = false,
    ): List<MusicTrack> {
        if (qqPersonalizedAccountId(cookie).isBlank() || qqCredentialMusicKey(cookie).isBlank()) {
            throw PlatformApiException("登录 QQ 音乐后才能使用猜你喜欢", 301)
        }
        val personalizedComm = JSONObject().apply {
            qqPersonalizedCommValues(cookie).forEach { (key, value) -> put(key, value) }
        }
        val body = JSONObject()
            .put("comm", personalizedComm)
            .put("songlist", JSONObject()
                .put("module", "music.radioProxy.MbTrackRadioSvr")
                .put("method", "get_radio_track")
                .put("param", qqRadioParameters(count)))
        val tracks = postMusicU(body, cookie).parseQqRadioTracks(hasVipAccess)
        if (tracks.isEmpty()) throw PlatformApiException("QQ 音乐没有返回推荐歌曲")
        return QqTrackAccessResolver().resolve(tracks.take(count), cookie)
    }

    fun playlistDetail(playlist: MusicPlaylist, cookie: String, hasVipAccess: Boolean = false): MusicPlaylist {
        val id = playlist.remoteId()
        val url = "$PLAYLIST_API?type=1&json=1&utf8=1&onlysong=0&disstid=${id.urlEncoded()}&format=json&g_tk=5381&loginUin=0&hostUin=0&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq&needNewCode=0"
        val raw = PlatformHttp.get(url, cookie, qqHeaders("https://y.qq.com/")).text.qqJsonText()
        val detail = JSONObject(raw).parseQqPlaylist(playlist, hasVipAccess)
        return detail.copy(tracks = QqTrackAccessResolver().resolve(detail.tracks, cookie))
    }

    suspend fun playbackSource(
        track: MusicTrack,
        cookie: String,
        quality: AudioQuality,
        inspectMedia: Boolean = true,
        guid: String? = null,
        origin: QqRequestOrigin = QqRequestOrigin.PLAYBACK,
    ): PlaybackSource {
        QqSessionRequestCoordinator.rememberGuid(cookie, guid)
        return retryQqRequestAfterVerification(cookie, interactive = origin != QqRequestOrigin.PRELOAD) { activeCookie ->
            currentCoroutineContext().ensureActive()
            QqSessionRequestCoordinator.beforeTicketRequest(activeCookie, showVerification = origin != QqRequestOrigin.PRELOAD)
            playbackSources.get(track, activeCookie, quality)
                ?: playbackSourceOnce(track, activeCookie, quality, inspectMedia, origin).also {
                    currentCoroutineContext().ensureActive()
                    playbackSources.remember(track, activeCookie, it)
                }
        }
    }

    private suspend fun playbackSourceOnce(
        track: MusicTrack,
        cookie: String,
        quality: AudioQuality,
        inspectMedia: Boolean,
        origin: QqRequestOrigin,
    ): PlaybackSource {
        // 短卡和部分详情缺少文件尺寸，实际可用档位以本次签发并校验的音源为准。
        awaitTicketRequest(cookie, origin)
        val primary = try {
            requestPlayback(track, cookie, quality, origin).optJSONObject("req_1")?.optJSONObject("data")
                ?.also { it.throwIfQqPlaybackItemsFailed(cookie, origin) }
        } catch (_: QqPlaybackItemUnavailableException) {
            null
        }
        val primaryUrl = primary?.let { resolveQqPlaybackUrl(it, quality) }
        val primaryFull = primaryUrl != null &&
            (!primaryUrl.isQqTrialUrl() || !inspectMedia || QqAudioValidation.isFullLength(primaryUrl, track))
        // Android 换票偶尔只返回试听重定向；热切换时继续取网页兼容票，优先完整音源。
        val compatibilityUrl = if (!primaryFull) try {
                awaitTicketRequest(cookie, origin)
                requestCompatibilityPlayback(track, cookie, quality, origin)
                    .optJSONObject("req_1")?.optJSONObject("data")
                    ?.also { it.throwIfQqPlaybackItemsFailed(cookie, origin) }
                    ?.let { resolveQqPlaybackUrl(it, quality) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (error.stopsPlaybackFallback()) throw error
                null
            } else null
        val compatibilityFull = compatibilityUrl != null &&
            (!compatibilityUrl.isQqTrialUrl() || !inspectMedia || QqAudioValidation.isFullLength(compatibilityUrl, track))
        val url = (when {
            primaryFull -> primaryUrl
            compatibilityFull -> compatibilityUrl
            else -> preferredQqPlaybackUrl(primaryUrl, compatibilityUrl)
        })
            ?: throw PlatformApiException(track.unavailableReason ?: "歌曲暂无可用播放地址")
        val actualQuality = qqPlaybackQuality(url)
            ?: throw PlatformApiException("QQ 音乐返回了无法识别的音源")
        if (actualQuality != quality) {
            throw PlatformApiException("QQ 音乐未返回${quality.displayLabel(MusicSource.QQ)}音源")
        }
        val spec = qqQualitySpec(actualQuality)
        // Hi-Res 票据可能指向不存在或降档的文件；必须确认后才能结束逐档回退。
        // 点播仅对 Hi-Res 读取 42 字节头，普通无损规格仍留到播放后加载。
        val flac = if (actualQuality == AudioQuality.HI_RES ||
            (inspectMedia && actualQuality == AudioQuality.LOSSLESS)) {
            readQqFlacInfo(url, timeoutMs = if (inspectMedia) 4_000 else 1_500)
        } else null
        if (actualQuality == AudioQuality.HI_RES &&
            (flac?.highResolution != true ||
                (track.durationMs > 0 && !qqMediaDurationMatches(flac.durationMs, track.durationMs)))) {
            throw PlatformApiException("未获取到可验证的完整 Hi-Res 音源")
        }
        return PlaybackSource(
            url = url,
            requestedQuality = quality,
            actualQuality = actualQuality,
            bitRate = actualQuality.bitRate,
            format = spec.extension,
            trial = !(url == primaryUrl && primaryFull) && !(url == compatibilityUrl && compatibilityFull),
            verificationPending = !inspectMedia && url.isQqTrialUrl(),
        )
    }

    suspend fun availableQualities(
        track: MusicTrack,
        cookie: String,
        requestedQualities: List<AudioQuality> = QQ_AUDIO_QUALITIES,
        guid: String? = null,
        onProgress: (List<AudioQuality>) -> Unit = {},
    ): List<AudioQuality> = coroutineScope {
        QqSessionRequestCoordinator.rememberGuid(cookie, guid)
        retryQqRequestAfterVerification(cookie) { activeCookie ->
            // 使用真实点播链路逐档确认，不能把已签发的文件名直接当作可用音质。
            probeQqAvailableQualities(requestedQualities, onProgress) { quality ->
                currentCoroutineContext().ensureActive()
                QqSessionRequestCoordinator.beforeTicketRequest(activeCookie)
                playbackSources.get(track, activeCookie, quality)
                    ?: playbackSourceOnce(track, activeCookie, quality, true, QqRequestOrigin.QUALITY_PROBE).also {
                        currentCoroutineContext().ensureActive()
                        playbackSources.remember(track, activeCookie, it)
                    }
            }
        }
    }

    private suspend fun awaitTicketRequest(cookie: String, origin: QqRequestOrigin) {
        currentCoroutineContext().ensureActive()
        if (origin == QqRequestOrigin.BATCH_CACHE || origin == QqRequestOrigin.PRELOAD) {
            QqSessionRequestCoordinator.beforeCacheTicketRequest(cookie, showVerification = origin != QqRequestOrigin.PRELOAD)
        }
        else QqSessionRequestCoordinator.beforeTicketRequest(cookie)
        currentCoroutineContext().ensureActive()
    }

    fun lyrics(track: MusicTrack, cookie: String, origin: QqRequestOrigin = QqRequestOrigin.OTHER): List<TimedLyric> {
        val body = JSONObject()
            .put("comm", JSONObject()
                .put("ct", 19)
                .put("cv", 0)
                .put("tmeAppID", "qqmusiclight"))
            .put("lyric", JSONObject()
                .put("module", "music.musichallSong.PlayLyricInfo")
                .put("method", "GetPlayLyricInfo")
                .put("param", JSONObject()
                    .put("songMID", track.qqPlaybackMid())
                    .put("songID", track.catalogId.toLongOrNull() ?: 0L)
                    .put("platform", 0)
                    .put("needNew", 1)
                    .put("crypt", 0)
                    .put("qrc", 0)
                    .put("roma", 0)
                    .put("trans", 1)))
        runCatching {
            postMusicU(body, cookie, origin = origin).optJSONObject("lyric")?.optJSONObject("data")
        }.onFailure { error ->
            // 后台歌词遇到验证直接结束，不能弹窗或继续尝试备用接口。
            if (error is CancellationException ||
                origin == QqRequestOrigin.PRELOAD && error is PlatformSecurityVerificationRequired) throw error
        }.getOrNull()?.let { data ->
            val original = parseLrc(decodeQqLyric(data.optString("lyric")))
            if (original.isNotEmpty()) {
                val translated = parseLrc(decodeQqLyric(data.optString("trans")))
                return mergeLyricTranslation(original, translated)
            }
        }
        val url = "$LYRIC_API?songmid=${track.qqPlaybackMid().urlEncoded()}&format=json&nobase64=1"
        val fallback = JSONObject(PlatformHttp.get(url, cookie, qqHeaders("https://y.qq.com/portal/player.html")).text.qqJsonText())
        return parseLrc(decodeQqLyric(fallback.optString("lyric")))
    }

    fun likedTrackIds(cookie: String, userId: String): Set<String> {
        if (cookie.isBlank()) throw PlatformApiException("请先登录 QQ 音乐", 301)
        val query = profileAssetQuery(userId, "1", 0, 500)
        val response = JSONObject(PlatformHttp.get("$FAVORITE_API?$query", cookie, qqHeaders("https://y.qq.com/")).text)
        if (response.optInt("code", -1) != 0) throw PlatformApiException("QQ 音乐我喜欢加载失败")
        val songs = response.optJSONObject("data")?.optJSONArray("songlist") ?: JSONArray()
        return buildSet {
            for (index in 0 until songs.length()) {
                val item = songs.optJSONObject(index)?.optJSONObject("data")
                    ?: songs.optJSONObject(index)
                    ?: continue
                // 正常歌曲用 songmid 作身份，音乐流短卡在补全前只有 songid；两者都缓存，
                // 这样收藏状态可以先命中短卡，详情补全后也不会丢失。
                item.optString("songmid").ifBlank { item.optString("mid") }
                    .takeIf(String::isNotBlank)?.let { add("qq-$it") }
                item.optString("songid").ifBlank { item.optString("id") }
                    .toLongOrNull()?.takeIf { it > 0L }?.let { add("qq-$it") }
            }
        }
    }

    fun setTrackLiked(cookie: String, track: MusicTrack, liked: Boolean) {
        setTracksLiked(cookie, listOf(track), liked)
    }

    /** 补全个性化接口偶尔缺失的专辑封面、文件标识和音质尺寸。 */
    suspend fun enrichTrackMetadata(
        track: MusicTrack,
        cookie: String,
        hasVipAccess: Boolean = false,
    ): MusicTrack = coroutineScope {
        val request = JSONObject()
            .put("module", "music.pf_song_detail_svr")
            .put("method", "get_song_detail_yqq")
            .put("param", JSONObject()
                .put("song_mid", track.qqPlaybackMid())
                .put("song_type", track.providerType)
                .put("song_id", track.catalogId.toLongOrNull() ?: 0L))
        val detailRequest = async {
            runCatching {
                postMusicU(
                    JSONObject().put("comm", commonRequest(cookie)).put("req_1", request),
                    cookie,
                ).optJSONObject("req_1")?.optJSONObject("data")?.optJSONObject("track_info")
                    ?.toQqTrack(hasVipAccess)
            }.getOrNull()
        }
        val fallbackRequest: kotlinx.coroutines.Deferred<List<MusicTrack>>? = if (track.artworkUrl.isNullOrBlank()) async {
            runCatching {
                searchTracks("${track.title} ${track.artists}", cookie, hasVipAccess)
            }.getOrDefault(emptyList())
        } else null
        val detail = detailRequest.await()
        val resolvedDetail = detail?.let { QqTrackAccessResolver().resolve(listOf(it), cookie).first() }
        val enriched = resolvedDetail?.let(track::mergeQqTrackMetadata) ?: track
        if (detail != null && enriched.qualityIds.isNotEmpty() && !enriched.artworkUrl.isNullOrBlank()) {
            return@coroutineScope enriched
        }
        // 详情失败时搜索结果的根级 size128/size320/sizeflac 也是音质校验后备来源。
        val candidates = fallbackRequest?.await() ?: runCatching {
            searchTracks("${track.title} ${track.artists}", cookie, hasVipAccess)
        }.getOrDefault(emptyList())
        val exact = candidates.firstOrNull { candidate ->
            candidate.id == track.id ||
                (track.catalogId.isNotBlank() && candidate.catalogId == track.catalogId)
        }
        if (enriched.qualityIds.isEmpty() && exact == null) {
            throw PlatformApiException("QQ 音乐没有返回可验证的音质信息")
        }
        val withMetadata = when {
            detail == null && exact != null -> track.mergeQqTrackMetadata(exact)
            enriched.qualityIds.isEmpty() && exact != null -> enriched.copy(qualityIds = exact.qualityIds)
            else -> enriched
        }
        withMetadata.withQqArtworkFallback(candidates)
    }

    fun setTracksLiked(cookie: String, tracks: List<MusicTrack>, liked: Boolean) {
        val mutation = qqFavoriteMutation(tracks, liked)
        val response = postMusicU(
            body = qqFavoriteRequestBody(cookie, mutation),
            cookie = cookie,
            headers = qqAndroidHeaders(),
            connectTimeoutMs = FAVORITE_CONNECT_TIMEOUT_MS,
            readTimeoutMs = FAVORITE_READ_TIMEOUT_MS,
        )
        requireQqFavoriteMutationSuccess(response)
    }

    private fun requestPlayback(
        track: MusicTrack,
        cookie: String,
        quality: AudioQuality,
        origin: QqRequestOrigin,
    ): JSONObject {
        val spec = qqQualitySpec(quality)
        val mid = track.qqPlaybackMid()
        val filenames = qqPlaybackFilenames(spec.prefix, spec.extension, mid, track.mediaId)
        return requestPlaybackValues(
            mid,
            track.providerType,
            filenames,
            cookie,
            platform = "23",
            quality = spec.prefix,
            origin = origin,
        )
    }

    private fun requestCompatibilityPlayback(
        track: MusicTrack,
        cookie: String,
        quality: AudioQuality,
        origin: QqRequestOrigin,
    ): JSONObject {
        val spec = qqQualitySpec(quality)
        val mid = track.qqPlaybackMid()
        val filenames = qqPlaybackFilenames(spec.prefix, spec.extension, mid, track.mediaId)
        return requestPlaybackValues(
            mid,
            track.providerType,
            filenames,
            cookie,
            platform = "20",
            origin = if (origin == QqRequestOrigin.PLAYBACK) QqRequestOrigin.COMPATIBILITY else origin,
        )
    }

    private fun requestPlaybackValues(
        mid: String,
        providerType: Int,
        filenames: List<String>,
        cookie: String,
        platform: String,
        quality: String? = null,
        origin: QqRequestOrigin = QqRequestOrigin.OTHER,
    ): JSONObject {
        QqSessionRequestCoordinator.beforeTicketRequest(cookie)
        val uin = normalizeUin(cookie).ifBlank { "0" }
        val mids = JSONArray()
        val types = JSONArray()
        val names = JSONArray()
        filenames.forEach { filename ->
            mids.put(mid)
            types.put(providerType)
            names.put(filename)
        }
        val params = JSONObject()
            .put("guid", QqSessionRequestCoordinator.guid(cookie))
            .put("songmid", mids)
            .put("songtype", types)
            .put("uin", uin)
            .put("loginflag", 1)
            .put("platform", platform)
            .put("filename", names)
        if (platform == "23") {
            params
                .put("h5queryversion", 1)
                .put("nettype", "")
                .put("cms", 0)
                .put("firstlogin", 1)
                .put("newver", 1)
                .put("nohash", 0)
            quality?.let { params.put("quality", it) }
        }
        val request = JSONObject()
            .put("module", "music.vkey.GetVkey")
            .put("method", "UrlGetVkey")
            .put("param", params)
        val comm = if (platform == "20") commonRequest(cookie) else qqPlaybackComm(cookie)
        val headers = if (platform == "20") qqHeaders("https://y.qq.com/") else qqAndroidHeaders()
        return postMusicU(JSONObject().put("comm", comm).put("req_1", request), cookie, headers, origin = origin)
    }

    private fun postMusicU(
        body: JSONObject,
        cookie: String,
        headers: Map<String, String> = qqHeaders("https://y.qq.com/"),
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 25_000,
        origin: QqRequestOrigin = QqRequestOrigin.OTHER,
    ): JSONObject {
        val startedAt = System.nanoTime()
        var success = false
        return try {
            val response = PlatformHttp.postJson(
                url = MUSIC_U_API,
                body = body.toString(),
                cookie = cookie,
                headers = headers,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
            )
            requireQqMusicUSuccess(JSONObject(response.text), cookie, origin).also { success = true }
        } finally {
            if (origin != QqRequestOrigin.OTHER) qqDiagnostic(
                "取票POST：来源=${origin.diagnosticName}，耗时=${(System.nanoTime() - startedAt) / 1_000_000}ms，成功=$success，" +
                    qqTicketContextDiagnostic(body, cookie),
            )
        }
    }

    private fun commonRequest(cookie: String): JSONObject {
        val uin = normalizeUin(cookie).ifBlank { "0" }
        return JSONObject()
            .put("cv", 4747474).put("ct", 24).put("format", "json")
            .put("inCharset", "utf-8").put("outCharset", "utf-8")
            .put("notice", 0).put("platform", "yqq.json").put("needNewCode", 1)
            .put("uin", uin)
    }

    private fun profileAssetQuery(userId: String, reqType: String, offset: Int, limit: Int): String = linkedMapOf(
        "format" to "json", "inCharset" to "utf8", "outCharset" to "utf-8", "platform" to "yqq.json",
        "needNewCode" to "0", "loginUin" to userId, "hostUin" to "0", "notice" to "0", "g_tk" to "5381",
        "ct" to "20", "cid" to "205360956", "userid" to userId, "reqtype" to reqType,
        "sin" to offset.toString(), "ein" to (offset + limit - 1).toString(),
    ).entries.joinToString("&") { (key, value) -> "${key.urlEncoded()}=${value.urlEncoded()}" }

    companion object {
        private const val SEARCH_API = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp"
        private const val PLAYLIST_API = "https://i.y.qq.com/qzone-music/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg"
        private const val MUSIC_U_API = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        private const val LYRIC_API = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
        private const val FAVORITE_API = "https://c.y.qq.com/fav/fcgi-bin/fcg_get_profile_order_asset.fcg"
        private const val PROFILE_API = "https://c6.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg"
        private const val SEARCH_REFERER = "https://y.qq.com/portal/search.html"
        private const val FAVORITE_CONNECT_TIMEOUT_MS = 8_000
        private const val FAVORITE_READ_TIMEOUT_MS = 12_000
    }
}

internal fun preferredQqPlaybackUrl(primary: String?, compatibility: String?): String? = when {
    compatibility != null && !compatibility.isQqTrialUrl() -> compatibility
    primary != null -> primary
    else -> compatibility
}

private data class QqQualitySpec(val prefix: String, val extension: String)

private fun qqQualitySpec(quality: AudioQuality): QqQualitySpec = when (quality) {
    AudioQuality.STANDARD -> QqQualitySpec("M500", "mp3")
    AudioQuality.HIGHER -> QqQualitySpec("C600", "m4a")
    AudioQuality.EXHIGH -> QqQualitySpec("M800", "mp3")
    AudioQuality.LOSSLESS -> QqQualitySpec("F000", "flac")
    AudioQuality.HI_RES -> QqQualitySpec("RS01", "flac")
    AudioQuality.DOLBY -> QqQualitySpec("D004", "mp4")
}

private fun hash33(value: String, seed: Int = 0): Int {
    var hash = seed
    value.forEach { hash += (hash shl 5) + it.code }
    return hash and 0x7fffffff
}

internal fun qqPlaybackFilename(prefix: String, extension: String, songMid: String, mediaMid: String): String =
    if (mediaMid.isBlank()) "$prefix$songMid$songMid.$extension" else "$prefix$mediaMid.$extension"

internal fun qqPlaybackFilenames(
    prefix: String,
    extension: String,
    songMid: String,
    mediaMid: String,
): List<String> = buildList {
    val effectiveMediaMid = mediaMid.ifBlank { songMid }
    add(qqPlaybackFilename(prefix, extension, songMid, effectiveMediaMid))
    add("$prefix$effectiveMediaMid$effectiveMediaMid.$extension")
    add("$prefix$songMid.$extension")
    add("$prefix$songMid$songMid.$extension")
}.distinct()

private fun decodeQqLyric(raw: String): String = if (raw.contains('[')) raw else runCatching {
    String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8)
}.getOrDefault(raw)

private fun JSONObject.qqPlaybackPurls(expectedQuality: AudioQuality? = null): List<String> {
    val data = this
    val infos = data.optJSONArray("midurlinfo") ?: return emptyList()
    return buildList {
        for (index in 0 until infos.length()) {
            val info = infos.optJSONObject(index) ?: continue
            if (info.optInt("result", 0) != 0) continue
            listOf(info.optString("purl"), info.optString("wifiurl"))
                .filter { url ->
                    url.isNotBlank() && (expectedQuality == null || qqPlaybackQuality(url) == expectedQuality)
                }
                .forEach(::add)
        }
    }
}

private fun resolveQqPlaybackUrl(
    data: JSONObject,
    expectedQuality: AudioQuality? = null,
): String? {
    val candidates = qqPlaybackUrlCandidates(data.qqPlaybackPurls(expectedQuality), data.qqPlaybackSips())
    return PlatformHttp.firstReadable(candidates)
}

private fun JSONObject.qqPlaybackSips(): List<String> = optJSONArray("sip")?.let { values ->
    (0 until values.length()).map(values::optString)
}.orEmpty()

internal fun qqAvailableQualitiesFromData(
    data: JSONObject?,
    expectedQualities: List<AudioQuality>,
): List<AudioQuality> {
    val infos = data?.optJSONArray("midurlinfo") ?: return emptyList()
    val candidateUrls = (0 until infos.length()).map { index ->
        val info = infos.optJSONObject(index)
        if (info == null || info.optInt("result", 0) != 0) emptyList() else {
            listOf(info.optString("purl"), info.optString("wifiurl"))
        }
    }
    return qqAvailableQualitiesFromReturnedUrls(expectedQualities, candidateUrls)
}

internal fun qqAvailableQualitiesFromReturnedUrls(
    expectedQualities: List<AudioQuality>,
    returnedUrls: List<List<String>>,
): List<AudioQuality> {
    val returned = qqAvailableQualitiesFromUrls(returnedUrls).toSet()
    return expectedQualities.distinct().filter(returned::contains)
}

internal fun qqAvailableQualitiesFromExpectedUrls(
    expectedQualities: List<AudioQuality>,
    candidateUrls: List<List<String>>,
): List<AudioQuality> = buildList {
    expectedQualities.forEachIndexed { index, expected ->
        val exact = candidateUrls.getOrNull(index).orEmpty().any { url ->
            url.isNotBlank() && !url.isQqTrialUrl() && qqPlaybackQuality(url) == expected
        }
        if (exact) add(expected)
    }
}.distinct()

internal fun qqAvailableQualitiesFromUrls(
    candidateUrls: List<List<String>>,
): List<AudioQuality> = candidateUrls.asSequence()
    .flatten()
    .filter { it.isNotBlank() && !it.isQqTrialUrl() }
    .mapNotNull(::qqPlaybackQuality)
    .distinct()
    .toList()

internal fun qqPlaybackQuality(url: String): AudioQuality? {
    val normalized = if (url.startsWith("//")) "https:$url" else url
    val path = runCatching {
        if (normalized.startsWith("http://", true) || normalized.startsWith("https://", true)) {
            URI.create(normalized).rawPath.orEmpty()
        } else {
            normalized.substringBefore('?').substringBefore('#')
        }
    }.getOrDefault(normalized.substringBefore('?').substringBefore('#'))
    val filename = path.substringAfterLast('/')
    return QQ_AUDIO_QUALITIES.firstOrNull { quality ->
        val spec = qqQualitySpec(quality)
        filename.startsWith(spec.prefix, ignoreCase = true) &&
            filename.endsWith(".${spec.extension}", ignoreCase = true)
    }
}

internal fun qqPlaybackUrlCandidates(purls: List<String>, responseSips: List<String>): List<String> {
    val bases = responseSips.mapNotNull { raw ->
        raw.trim().takeIf(String::isNotBlank)?.let { value ->
            when {
                value.startsWith("//") -> "https:$value"
                value.startsWith("http://", true) || value.startsWith("https://", true) ->
                    value.upgradePlaybackUrlToHttps()
                else -> null
            }
        }
    }.map { it.trimEnd('/') + "/" }.distinct()
    return buildList {
        for (purl in purls.filter(String::isNotBlank)) {
            val normalizedPurl = if (purl.startsWith("//")) "https:$purl" else purl
            val absolute = normalizedPurl.startsWith("http://", true) || normalizedPurl.startsWith("https://", true)
            val path = if (absolute) runCatching {
                val uri = URI.create(normalizedPurl)
                uri.rawPath.orEmpty().trimStart('/') + uri.rawQuery?.let { "?$it" }.orEmpty()
            }.getOrDefault(normalizedPurl) else normalizedPurl.trimStart('/')
            if (absolute) add(normalizedPurl.upgradePlaybackUrlToHttps())
            bases.forEach { base -> add(base + path) }
        }
    }.distinct()
}

internal fun qqPlaybackComm(cookie: String): JSONObject {
    val values = cookie.cookieValues()
    val musicId = qqCredentialAccountId(cookie)
    val musicKey = qqMusicKey(cookie)
    val loginType = values["loginType"]?.toIntOrNull()
        ?: if (musicKey.startsWith("W_X")) 1 else 2
    return JSONObject()
        .put("ct", 11).put("cv", 14090008).put("v", 14090008)
        .put("chid", "10003505").put("qq", musicId).put("authst", musicKey)
        .put("tmeAppID", "qqmusic").put("tmeLoginType", loginType)
        .put("format", "json")
}

internal fun qqAccountFromProfile(
    userId: String,
    cookie: String,
    response: JSONObject?,
    hasVipAccess: Boolean = false,
): MusicAccount {
    val values = cookie.cookieValues()
    val data = response?.optJSONObject("data")
    val creator = data?.optJSONObject("creator") ?: data
    val nickname = listOf("nick", "nickname", "name", "hostname")
        .firstNotNullOfOrNull { name -> creator?.optString(name)?.takeIf(String::isNotBlank) }
        ?: values["nickname"].orEmpty().ifBlank { "QQ 用户 $userId" }
    val avatar = listOf("headpic", "avatar", "avatarUrl", "logo")
        .firstNotNullOfOrNull { name -> creator?.optString(name)?.takeIf(String::isNotBlank) }
        ?: listOf("avatar", "avatarUrl", "headpic")
            .firstNotNullOfOrNull { name -> values[name]?.takeIf(String::isNotBlank) }
        ?: userId.takeIf { it.all(Char::isDigit) }?.let { "https://q1.qlogo.cn/g?b=qq&nk=$it&s=640" }
    return MusicAccount(
        MusicSource.QQ,
        userId,
        nickname,
        avatar?.let { if (it.startsWith("//")) "https:$it" else it.replaceFirst("http://", "https://") },
        hasVipAccess,
    )
}

private fun qqMusicKey(cookie: String): String {
    val values = cookie.cookieValues()
    return listOf("musickey", "qqmusic_key", "qm_keyst", "authst", "p_skey", "skey")
        .firstNotNullOfOrNull { values[it]?.takeIf(String::isNotBlank) }.orEmpty()
}

/** 个性化推荐接口必须显式携带登录账号和 authst，不能复用播放换票的移动端信封。 */
internal fun qqPersonalizedCommValues(credential: String): Map<String, Any> = linkedMapOf(
    "uin" to qqPersonalizedAccountId(credential),
    "format" to "json",
    "ct" to 19,
    "cv" to 0,
    "authst" to qqCredentialMusicKey(credential),
)

internal fun qqPersonalizedAccountId(credential: String): String = qqCredentialAccountId(credential)

internal fun qqCredentialMusicKey(credential: String): String = qqMusicKey(credential)

private fun normalizeUin(cookie: String): String = qqCredentialAccountId(cookie)

private fun qqHeaders(referer: String): Map<String, String> = mapOf(
    "Referer" to referer,
    "Origin" to "https://y.qq.com",
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
)

private fun qqAndroidHeaders(): Map<String, String> = mapOf(
    "Referer" to "https://y.qq.com/",
    "User-Agent" to "QQMusic 14090008(android 14)",
)

private fun String.qqComparableSongTitle(): String = lowercase()
    .replace('（', '(')
    .replace('）', ')')
    .replace(Regex("\\s+"), "")

private fun String.qqJsonText(): String {
    val start = indexOf('{')
    val end = lastIndexOf('}')
    return if (start >= 0 && end >= start) substring(start, end + 1) else this
}

internal fun String.isQqTrialUrl(): Boolean =
    contains("?src=", ignoreCase = true) || contains("&src=", ignoreCase = true)
