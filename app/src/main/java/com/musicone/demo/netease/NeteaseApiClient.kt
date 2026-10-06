package com.musicone.demo

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class NeteaseCaptchaResult(
    val message: String,
    val cookie: String,
)

internal data class NeteaseLoginResult(
    val cookie: String,
    val account: MusicAccount,
)

internal data class NeteaseQrCode(
    val key: String,
    val url: String,
    val cookie: String,
)

internal data class NeteaseQrCheckResult(
    val status: PlatformQrLoginStatus,
    val message: String,
    val cookie: String,
    val account: MusicAccount? = null,
)

internal class NeteaseApiException(
    message: String,
    val apiCode: Int? = null,
) : Exception(message)

internal class NeteaseApiClient {
    fun sendCaptcha(phone: String, countryCode: String, deviceId: String, cookie: String = ""): NeteaseCaptchaResult {
        val (normalizedPhone, normalizedCountryCode) = normalizePhone(phone, countryCode)
        val response = postMobileEapi(
            apiPath = "/api/sms/captcha/sent",
            payload = JSONObject()
                .put("cellphone", normalizedPhone)
                .put("ctcode", normalizedCountryCode)
                .put("os", "iOS")
                .put("fromPage", "RN")
                .put("rnBundleVersion", "0.0.5")
                .put("rnBundleName", "new-rn-login")
                .put("verifyId", 1)
                .put("e_r", true),
            deviceId = deviceId,
            cookie = cookie,
        )
        response.body.throwIfNeteaseSecurityVerificationRequired(mergeCookies(cookie, response.cookie))
        response.body.requireSuccess("验证码发送失败")
        return NeteaseCaptchaResult(
            message = response.body.apiMessage().ifBlank { "验证码已发送" },
            cookie = mergeCookies(cookie, response.cookie),
        )
    }

    fun loginByCaptcha(
        phone: String,
        countryCode: String,
        captcha: String,
        deviceId: String,
        cookie: String,
    ): NeteaseLoginResult {
        val (normalizedPhone, normalizedCountryCode) = normalizePhone(phone, countryCode)
        val normalizedCaptcha = captcha.trim()
        if (normalizedCaptcha.length !in 4..10 || normalizedCaptcha.any { !it.isDigit() }) {
            throw NeteaseApiException("请输入正确的验证码")
        }
        val response = postMobileEapi(
            apiPath = "/api/login/cellphone",
            payload = JSONObject()
                .put("type", "1")
                .put("https", "true")
                .put("phone", normalizedPhone)
                .put("countrycode", normalizedCountryCode)
                .put("captcha", normalizedCaptcha)
                .put("remember", "true")
                .put("rememberLogin", "true")
                .put("os", "iOS")
                .put("fromPage", "RN")
                .put("rnBundleVersion", "0.0.5")
                .put("rnBundleName", "new-rn-login")
                .put("verifyId", 1)
                .put("e_r", true),
            deviceId = deviceId,
            cookie = cookie,
        )
        response.body.throwIfNeteaseSecurityVerificationRequired(mergeCookies(cookie, response.cookie))
        response.body.requireSuccess("登录失败")
        val mergedCookie = mergeCookies(cookie, response.cookie, response.body.optString("cookie"))
        if (mergedCookie.isBlank()) throw NeteaseApiException("网易云没有返回登录凭据")
        return NeteaseLoginResult(mergedCookie, account(mergedCookie))
    }

    fun account(cookie: String): MusicAccount {
        if (cookie.isBlank()) throw NeteaseApiException("尚未登录", 301)
        val response = getJson(ACCOUNT_API, cookie)
        response.requireSuccess("登录状态检查失败")
        val summary = response.toMusicAccount()
        return runCatching {
            val details = getJson("$USER_DETAIL_API/${summary.userId}", cookie).also {
                it.requireSuccess("用户资料加载失败")
            }.toMusicAccount()
            details.copy(hasVipAccess = summary.hasVipAccess || details.hasVipAccess)
        }.getOrDefault(summary)
    }

    fun createQrCode(cookie: String = ""): NeteaseQrCode {
        val response = postForm(
            url = QR_KEY_API,
            values = mapOf("type" to "3"),
            cookie = cookie,
            headers = mapOf("User-Agent" to QR_LOGIN_USER_AGENT),
        )
        response.body.requireSuccess("二维码生成失败")
        val key = response.body.optString("unikey")
            .ifBlank { response.body.optJSONObject("data")?.optString("unikey").orEmpty() }
        if (key.isBlank()) throw NeteaseApiException("网易云没有返回二维码凭据")
        return NeteaseQrCode(
            key = key,
            url = "https://music.163.com/login?codekey=${key.formEncoded()}",
            cookie = mergeCookies(cookie, response.cookie),
        )
    }

    fun checkQrCode(key: String, cookie: String): NeteaseQrCheckResult {
        if (key.isBlank()) throw NeteaseApiException("二维码凭据无效")
        val response = postForm(
            url = QR_CHECK_API,
            values = mapOf("key" to key, "type" to "3"),
            cookie = cookie,
            headers = mapOf("User-Agent" to QR_LOGIN_USER_AGENT),
        )
        val code = response.body.optInt("code", -1)
        val mergedCookie = mergeCookies(cookie, response.cookie, response.body.optString("cookie"))
        response.body.throwIfNeteaseSecurityVerificationRequired(mergedCookie)
        val status = qrLoginStatus(code) ?: throw NeteaseApiException(
            response.body.apiMessage().ifBlank { "二维码登录失败（$code）" },
            code.takeIf { it > 0 },
        )
        val message = when (status) {
            PlatformQrLoginStatus.WAITING_SCAN -> "请使用网易云音乐扫码"
            PlatformQrLoginStatus.WAITING_CONFIRM -> "已扫码，请在手机上确认"
            PlatformQrLoginStatus.EXPIRED -> "二维码已过期，请刷新"
            else -> response.body.apiMessage()
        }
        if (code != 803) return NeteaseQrCheckResult(status, message, mergedCookie)
        if (mergedCookie.isBlank()) throw NeteaseApiException("网易云没有返回登录凭据")
        return NeteaseQrCheckResult(status, "登录成功", mergedCookie, account(mergedCookie))
    }

    fun search(query: String, cookie: String, hasVipAccess: Boolean = false): List<MusicTrack> {
        val normalized = query.trim()
        if (normalized.isBlank()) return emptyList()
        val forward = JSONObject()
            .put("method", "POST")
            .put("url", "http://music.163.com/api/cloudsearch/pc")
            .put("params", JSONObject().put("s", normalized).put("type", 1).put("offset", 0).put("limit", 20))
        val response = postForm(
            url = SEARCH_API,
            values = mapOf("eparams" to NeteaseCrypto.encryptLinux(forward.toString())),
            cookie = cookie,
        ).body
        response.requireSuccess("搜索失败")
        return response.parseSearchTracks(hasVipAccess)
    }

    fun recommendedPlaylists(cookie: String, limit: Int = 8, page: Int = 0): List<MusicPlaylist> {
        val response = getJson("$RECOMMENDED_API?limit=30", cookie)
        response.requireSuccess("推荐歌单加载失败")
        return recommendationWindow(response.parseRecommendedPlaylists(), limit.coerceIn(1, 30), page)
    }

    fun recommendedTracks(cookie: String, page: Int = 0, limit: Int = 12, hasVipAccess: Boolean = false): List<MusicTrack> {
        if (cookie.isNotBlank()) {
            val daily = runCatching {
                getJson(DAILY_TRACKS_API, cookie).also { it.requireSuccess("每日推荐歌曲加载失败") }
                    .parseDailyRecommendedTracks(hasVipAccess)
            }.getOrDefault(emptyList())
            if (daily.isNotEmpty()) return recommendationWindow(daily, limit, page)
        }
        val response = getJson("$PUBLIC_TRACKS_API?limit=30", cookie)
        response.requireSuccess("推荐歌曲加载失败")
        return recommendationWindow(response.parsePublicRecommendedTracks(hasVipAccess), limit, page)
    }

    fun likedTrackIds(cookie: String, userId: String): Set<String> {
        if (cookie.isBlank()) throw NeteaseApiException("请先登录网易云音乐", 301)
        requireNumericId(userId, "用户")
        val playlists = getJson("$USER_PLAYLIST_API?uid=${userId.formEncoded()}&offset=0&limit=50", cookie)
        playlists.requireSuccess("我喜欢的音乐加载失败")
        val values = playlists.optJSONArray("playlist")
        val likedPlaylistId = (0 until (values?.length() ?: 0))
            .mapNotNull { values?.optJSONObject(it) }
            .firstOrNull { it.optInt("specialType") == 5 }
            ?.optLong("id")
            ?.takeIf { it > 0L }
            ?: throw NeteaseApiException("没有找到我喜欢的音乐")
        val detail = getJson("$PLAYLIST_API?id=$likedPlaylistId&n=1000&s=0", cookie)
        detail.requireSuccess("我喜欢的音乐加载失败")
        return detail.optJSONObject("playlist")?.optJSONArray("trackIds")
            ?.let { ids -> buildSet { for (index in 0 until ids.length()) add("netease-${ids.optJSONObject(index)?.optLong("id")}") } }
            .orEmpty()
            .filterNotTo(linkedSetOf()) { it == "netease-0" }
    }

    fun userPlaylists(cookie: String, userId: String): List<MusicPlaylist> {
        if (cookie.isBlank()) throw NeteaseApiException("请先登录网易云音乐", 301)
        requireNumericId(userId, "用户")
        val response = getJson(
            "$USER_PLAYLIST_API?uid=${userId.formEncoded()}&offset=0&limit=100",
            cookie,
        )
        response.requireSuccess("我的歌单加载失败")
        return response.parseUserPlaylists(userId)
    }

    fun setTrackLiked(cookie: String, userId: String, trackId: String, liked: Boolean) {
        if (cookie.isBlank()) throw NeteaseApiException("请先登录网易云音乐", 301)
        requireNumericId(userId, "用户")
        requireNumericId(trackId, "歌曲")
        postEapi(
            LIKE_EAPI,
            JSONObject().put("trackId", trackId.toLong()).put("userid", userId.toLong()).put("like", liked),
            cookie,
            "我喜欢操作失败",
        )
    }

    suspend fun playlistDetail(id: String, cookie: String, hasVipAccess: Boolean = false): MusicPlaylist = coroutineScope {
        requireNumericId(id, "歌单")
        val response = getJson("$PLAYLIST_API?id=${id.formEncoded()}&n=1000&s=8", cookie)
        response.requireSuccess("歌单加载失败")
        val (playlist, trackIds) = response.parsePlaylistShell()
        val embeddedTracks = response.parsePlaylistEmbeddedTracks(hasVipAccess)
        val missingIds = missingPlaylistTrackIds(trackIds, embeddedTracks)
        val requestLimit = Semaphore(4)
        val loadedTracks = missingIds.chunked(100).map { ids ->
            async { requestLimit.withPermit { songDetails(ids, cookie, hasVipAccess) } }
        }.awaitAll().flatten()
        val tracks = orderedPlaylistTracks(trackIds, embeddedTracks + loadedTracks)
        playlist.copy(tracks = tracks, count = if (playlist.count > 0) playlist.count else tracks.size)
    }

    fun playbackSource(trackId: String, cookie: String, quality: AudioQuality): PlaybackSource {
        requireNumericId(trackId, "歌曲")
        if (cookie.isNotBlank()) {
            val payload = JSONObject()
                .put("ids", JSONArray().put(trackId.toLong()))
                .put("level", quality.providerLevel)
                .put("encodeType", if (quality >= AudioQuality.LOSSLESS) "flac" else "aac")
                .put("header", "{\"os\":\"pc\",\"appver\":\"\",\"osver\":\"\",\"deviceId\":\"pyncm!\",\"requestId\":\"12345678\"}")
            runCatching { postEapi(PLAYBACK_EAPI, payload, cookie, "播放地址获取失败").toPlaybackSource(quality) }
                .getOrNull()
                ?.let { return it }
        }
        if (quality > AudioQuality.EXHIGH) throw NeteaseApiException("当前账号无法播放${quality.label}")
        val ids = JSONArray().put(trackId.toLong()).toString().formEncoded()
        val response = getJson("$PLAYBACK_API?ids=$ids&br=${quality.bitRate}", cookie)
        response.requireSuccess("播放地址获取失败")
        return response.toPlaybackSource(quality)
    }

    fun lyrics(trackId: String, cookie: String): List<TimedLyric> {
        requireNumericId(trackId, "歌曲")
        val response = getJson("$LYRIC_API?id=${trackId.formEncoded()}&lv=-1&tv=-1&rv=-1&yv=-1", cookie)
        response.requireSuccess("歌词加载失败")
        val original = parseLrc(response.optJSONObject("lrc")?.optString("lyric").orEmpty())
        val translated = parseLrc(response.optJSONObject("tlyric")?.optString("lyric").orEmpty())
        return mergeLyricTranslation(original, translated)
    }

    private fun songDetails(ids: List<String>, cookie: String, hasVipAccess: Boolean): List<MusicTrack> {
        if (ids.isEmpty()) return emptyList()
        val idArray = JSONArray().also { array -> ids.forEach { array.put(it.toLong()) } }
        val response = getJson("$DETAIL_API?ids=${idArray.toString().formEncoded()}", cookie)
        response.requireSuccess("歌曲详情加载失败")
        return response.parseSongDetails(hasVipAccess)
    }

    private fun postEapi(url: String, payload: JSONObject, cookie: String, fallback: String): JSONObject {
        val params = NeteaseCrypto.encryptEapi(url, payload.toString())
        val response = postForm(url, mapOf("params" to params), cookie).body
        response.requireSuccess(fallback)
        return response
    }

    private fun postMobileEapi(apiPath: String, payload: JSONObject, deviceId: String, cookie: String): HttpResult {
        payload.put("deviceId", deviceId).put("header", JSONObject())
        val params = NeteaseCrypto.encryptEapi(apiPath, payload.toString())
        val baseCookie = "__remember_me=true; os=iPhone%20OS; osver=18.7.2; appver=9.5.37; buildver=7010; channel=distribution; deviceId=$deviceId; sDeviceId=$deviceId"
        return postForm(
            url = MOBILE_EAPI_BASE + apiPath.removePrefix("/api"),
            values = mapOf("params" to params),
            cookie = mergeCookies(baseCookie, cookie),
            headers = mapOf(
                "User-Agent" to "neteasemusic/9.5.37 (iPhone; iOS 18.7.2; Scale/3.00)",
                "X-AEAPI" to "true",
                "X-DeviceId" to deviceId,
                "X-SDeviceId" to deviceId,
                "X-OS" to "iPhone OS",
                "X-OSVer" to "18.7.2",
                "X-AppVer" to "9.5.37",
                "X-BuildVer" to "7010",
            ),
            decryptMobileResponse = true,
        )
    }

    private fun postForm(
        url: String,
        values: Map<String, String>,
        cookie: String = "",
        headers: Map<String, String> = emptyMap(),
        decryptMobileResponse: Boolean = false,
    ): HttpResult {
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 12_000
            connection.readTimeout = 25_000
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("Referer", "https://music.163.com/")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 MusicOne/0.1")
            if (cookie.isNotBlank()) connection.setRequestProperty("Cookie", cookie)
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            val encoded = values.entries.joinToString("&") { (key, value) -> "${key.formEncoded()}=${value.formEncoded()}" }
            connection.outputStream.use { it.write(encoded.toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            val rawBody = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes() }
                ?: ByteArray(0)
            val text = if (decryptMobileResponse && rawBody.isNotEmpty()) NeteaseCrypto.decryptMobileEapi(rawBody) else rawBody.toString(Charsets.UTF_8)
            val body = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (status !in 200..299) throw NeteaseApiException(body.apiMessage().ifBlank { "网易云请求失败（$status）" }, status)
            return HttpResult(body, responseCookies(connection))
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(url: String, cookie: String = ""): JSONObject = getHttpResult(url, cookie).body

    private fun getHttpResult(url: String, cookie: String = ""): HttpResult {
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 12_000
            connection.readTimeout = 25_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Referer", "https://music.163.com/")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 MusicOne/0.1")
            if (cookie.isNotBlank()) connection.setRequestProperty("Cookie", cookie)
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            val body = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (status !in 200..299) throw NeteaseApiException(body.apiMessage().ifBlank { "网易云请求失败（$status）" }, status)
            return HttpResult(body, responseCookies(connection))
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizePhone(phone: String, countryCode: String): Pair<String, String> {
        val normalizedPhone = phone.trim()
        val normalizedCountryCode = countryCode.trim().removePrefix("+").ifBlank { "86" }
        if (normalizedPhone.length !in 6..20 || normalizedPhone.any { !it.isDigit() }) {
            throw NeteaseApiException("请输入正确的手机号码")
        }
        if (normalizedCountryCode.length !in 1..4 || normalizedCountryCode.any { !it.isDigit() }) {
            throw NeteaseApiException("请输入正确的国家或地区代码")
        }
        return normalizedPhone to normalizedCountryCode
    }

    private fun requireNumericId(id: String, label: String) {
        if (id.isBlank() || id.any { !it.isDigit() }) throw NeteaseApiException("$label ID 无效")
    }

    private data class HttpResult(val body: JSONObject, val cookie: String)

    companion object {
        private const val SEARCH_API = "https://music.163.com/api/linux/forward"
        private const val ACCOUNT_API = "https://music.163.com/api/nuser/account/get"
        private const val USER_DETAIL_API = "https://music.163.com/api/v1/user/detail"
        private const val QR_KEY_API = "https://interface.music.163.com/api/login/qrcode/unikey"
        private const val QR_CHECK_API = "https://interface.music.163.com/api/login/qrcode/client/login"
        private const val QR_LOGIN_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36 NeteaseMusicDesktop/3.0.18.203152"
        private const val RECOMMENDED_API = "https://music.163.com/api/personalized/playlist"
        private const val DAILY_TRACKS_API = "https://music.163.com/api/v3/discovery/recommend/songs"
        private const val PUBLIC_TRACKS_API = "https://music.163.com/api/personalized/newsong"
        private const val USER_PLAYLIST_API = "https://music.163.com/api/user/playlist"
        private const val PLAYLIST_API = "https://music.163.com/api/v6/playlist/detail"
        private const val DETAIL_API = "https://music.163.com/api/song/detail"
        private const val PLAYBACK_API = "https://music.163.com/api/song/enhance/player/url"
        private const val PLAYBACK_EAPI = "https://interface3.music.163.com/eapi/song/enhance/player/url/v1"
        private const val LIKE_EAPI = "https://interface3.music.163.com/eapi/song/like"
        private const val LYRIC_API = "https://music.163.com/api/song/lyric"
        private const val MOBILE_EAPI_BASE = "https://interface3.music.163.com/eapi"
    }
}

internal fun qrLoginStatus(code: Int): PlatformQrLoginStatus? = when (code) {
    800 -> PlatformQrLoginStatus.EXPIRED
    801 -> PlatformQrLoginStatus.WAITING_SCAN
    802 -> PlatformQrLoginStatus.WAITING_CONFIRM
    803 -> PlatformQrLoginStatus.SUCCESS
    else -> null
}

internal fun <T> recommendationWindow(items: List<T>, limit: Int, page: Int): List<T> {
    if (items.isEmpty()) return emptyList()
    val size = minOf(limit.coerceAtLeast(1), items.size)
    val stride = if (items.size > size) size else maxOf(1, size / 2)
    val start = Math.floorMod(page, items.size) * stride % items.size
    return List(size) { index -> items[(start + index) % items.size] }
}

private fun String.formEncoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())

private fun JSONObject.toPlaybackSource(requestedQuality: AudioQuality): PlaybackSource {
    requireSuccess("播放地址获取失败")
    val value = optJSONArray("data")?.optJSONObject(0)
        ?: throw NeteaseApiException("歌曲暂无可用播放地址")
    val url = value.optString("url").takeIf(String::isNotBlank)?.asNeteaseHttpsUrl()
        ?: throw NeteaseApiException("歌曲暂无可用播放地址")
    val bitRate = value.optInt("br").coerceAtLeast(0)
    return PlaybackSource(
        url = url,
        requestedQuality = requestedQuality,
        actualQuality = AudioQuality.fromProvider(value.optString("level"), bitRate),
        bitRate = bitRate,
        format = value.optString("type").ifBlank { value.optString("encodeType") }.ifBlank { "audio" },
        trial = value.has("freeTrialInfo") && !value.isNull("freeTrialInfo"),
    )
}

private fun JSONObject.requireSuccess(fallback: String) {
    val code = optInt("code", -1)
    if (code != 200) {
        val message = apiMessage().ifBlank { if (code > 0) "$fallback（$code）" else fallback }
        throw NeteaseApiException(message, code.takeIf { it > 0 })
    }
}

private fun JSONObject.apiMessage(): String = optString("message").ifBlank { optString("msg") }

private fun JSONObject.throwIfNeteaseSecurityVerificationRequired(cookie: String) {
    val code = optInt("code", -1)
    val message = apiMessage()
    val challengeMessage = message.contains("安全风险") || message.contains("行为验证") ||
        message.contains("安全验证") || message.contains("风控")
    if (code !in setOf(8_821, 10_003) && !challengeMessage) return
    val containers = listOf(this, optJSONObject("data"), optJSONObject("result")).filterNotNull()
    val url = containers.firstNotNullOfOrNull { value ->
        listOf("securityURL", "securityUrl", "redirectUrl", "verifyUrl", "captchaUrl", "url")
            .firstNotNullOfOrNull { key -> value.optString(key).takeIf { it.startsWith("http") } }
    }.orEmpty().ifBlank { "https://music.163.com/m/login" }
    throw PlatformSecurityVerificationRequired(
        PlatformSecurityChallenge(
            source = MusicSource.NETEASE,
            kind = PlatformSecurityVerificationKind.WEB,
            title = "网易云音乐安全验证",
            message = "请在页面中完成账号或行为验证",
            url = url,
            credentialSeed = cookie,
        ),
    )
}

private fun responseCookies(connection: HttpURLConnection): String {
    val values = connection.headerFields.entries
        .filter { (name, _) -> name?.equals("Set-Cookie", ignoreCase = true) == true }
        .flatMap { it.value.orEmpty() }
        .map { it.substringBefore(';') }
    return mergeCookies(*values.toTypedArray())
}

internal fun mergeCookies(vararg values: String): String {
    val ignored = setOf("path", "domain", "expires", "max-age", "secure", "httponly", "samesite")
    val cookies = linkedMapOf<String, String>()
    values.forEach { value ->
        value.split(';').forEach { part ->
            val pair = part.trim().split('=', limit = 2)
            val name = pair.firstOrNull().orEmpty().trim()
            if (pair.size == 2 && name.isNotBlank() && name.lowercase() !in ignored) cookies[name] = pair[1].trim()
        }
    }
    return cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }
}
