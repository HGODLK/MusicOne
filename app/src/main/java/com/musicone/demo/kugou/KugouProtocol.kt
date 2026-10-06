package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random

internal object KugouProtocol {
    const val appId = "3116"
    const val clientVersion = "11440"
    const val liteKey = "185672dd44712f60bb1736df5a377e82"
    private const val songInfoSignKey = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
    private const val androidSignKey = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"

    fun signedSongInfoUrl(baseUrl: String, params: Map<String, String>): String {
        val signature = md5(songInfoSignKey + params.sortedSignaturePairs() + songInfoSignKey)
        return queryUrl(baseUrl, params + ("signature" to signature))
    }

    fun signedAndroidUrl(baseUrl: String, params: Map<String, String>, data: String): String {
        val signature = md5(androidSignKey + params.sortedSignaturePairs() + data + androidSignKey)
        return queryUrl(baseUrl, params + ("signature" to signature))
    }

    fun loginKey(timestamp: Long): String = md5(appId + androidSignKey + clientVersion + timestamp)

    fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun randomDfid(): String = md5(System.nanoTime().toString()).uppercase().take(24)

    fun createDeviceCookie(): String {
        val guid = UUID.randomUUID().toString()
        val mid = BigInteger(1, MessageDigest.getInstance("MD5").digest(guid.toByteArray())).toString()
        val chars = "1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        fun random(length: Int) = buildString { repeat(length) { append(chars[Random.nextInt(chars.length)]) } }
        return mapOf(
            "KUGOU_API_GUID" to guid,
            "KUGOU_API_MID" to mid,
            "KUGOU_API_MAC" to random(12),
            "KUGOU_API_DEV" to random(16),
        ).asCookieHeader()
    }
}

internal data class KugouSession(
    val cookie: String,
    val token: String,
    val userId: String,
    val mid: String,
    val dfid: String,
)

internal fun String.requiredKugouSession(): KugouSession {
    val values = cookieValues()
    val token = values["token"].orEmpty()
    val userId = values["userid"].orEmpty()
    val mid = values["KUGOU_API_MID"].orEmpty()
    if (token.isBlank() || userId.isBlank() || userId == "0" || mid.isBlank()) {
        throw PlatformApiException("请先登录酷狗音乐", 301)
    }
    return KugouSession(this, token, userId, mid, values["dfid"].orEmpty().ifBlank(KugouProtocol::randomDfid))
}

internal fun MusicTrack.kugouHashFor(quality: AudioQuality): String? = when (quality) {
    AudioQuality.STANDARD -> qualityIds[AudioQuality.STANDARD] ?: remoteId()
    AudioQuality.HIGHER -> null
    else -> qualityIds[quality]
}?.takeIf(String::isNotBlank)

internal fun AudioQuality.kugouQuality(): String = when (this) {
    AudioQuality.STANDARD -> "128"
    AudioQuality.HIGHER -> "128"
    AudioQuality.EXHIGH -> "320"
    AudioQuality.LOSSLESS -> "flac"
    AudioQuality.HI_RES -> "high"
    AudioQuality.DOLBY -> throw IllegalArgumentException("酷狗音乐不支持 Dolby 音质")
}

internal fun JSONObject.pickKugouUrl(): String {
    fun values(item: Any?): List<String> = when (item) {
        is String -> listOf(item)
        is JSONArray -> (0 until item.length())
            .mapNotNull { item.optString(it).takeIf(String::isNotBlank) }
        else -> emptyList()
    }
    val names = listOf(
        "url", "play_url", "tracker_url", "backup_url", "play_backup_url", "backupUrl", "mp3Url", "backupMp3Url",
    )
    val candidates = buildList {
        names.forEach { name -> addAll(values(opt(name))) }
        optJSONObject("data")?.let { data -> names.forEach { name -> addAll(values(data.opt(name))) } }
    }
    return pickKugouHttpsPlaybackUrl(candidates)
}

internal fun pickKugouHttpsPlaybackUrl(candidates: List<String>): String =
    candidates.firstNotNullOfOrNull(String::toKugouHttpsPlaybackUrlOrNull).orEmpty()

internal fun String.toKugouHttpsPlaybackUrlOrNull(): String? {
    val normalized = trim().replace("\\/", "/")
    if (normalized.startsWith("https://", ignoreCase = true)) return normalized
    if (!normalized.startsWith("http://", ignoreCase = true)) return null
    val host = runCatching { URI.create(normalized).host.orEmpty().lowercase() }.getOrDefault("")
    val hasWildcardCompatibleHost = host.endsWith(".kugou.com") && host.count { it == '.' } == 2
    return normalized.upgradePlaybackUrlToHttps().takeIf { hasWildcardCompatibleHost }
}

internal fun hasCompleteKugouTrackList(embeddedCount: Int, declaredCount: Int): Boolean =
    embeddedCount > 0 && (declaredCount <= 0 || embeddedCount >= declaredCount)

internal fun JSONObject.requireKugouSuccess(message: String) {
    val status = optInt("status", 1)
    val errorCode = optInt("error_code", optInt("errcode", 0))
    if (status !in 0..1 || errorCode != 0) throw PlatformApiException(optString("error").ifBlank { message })
}

internal fun kugouQueryUrl(baseUrl: String, params: Map<String, String>): String = baseUrl + "?" +
    params.entries.joinToString("&") { (key, value) -> "${key.urlEncoded()}=${value.urlEncoded()}" }

internal fun kugouMobileHeaders(): Map<String, String> = mapOf(
    "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148",
    "Referer" to "https://m.kugou.com/",
)

internal fun kugouPcHeaders(): Map<String, String> = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Referer" to "https://www.kugou.com/",
)

internal fun kugouAndroidHeaders(params: Map<String, String>, router: String?): Map<String, String> = buildMap {
    put("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi")
    put("dfid", params["dfid"].orEmpty())
    put("clienttime", params["clienttime"].orEmpty())
    put("mid", params["mid"].orEmpty())
    put("kg-rc", "1")
    put("kg-thash", "5d816a0")
    put("kg-rec", "1")
    put("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F")
    router?.let { put("x-router", it) }
}

private fun Map<String, String>.sortedSignaturePairs(): String =
    entries.sortedBy { it.key }.joinToString("") { "${it.key}=${it.value}" }

private fun queryUrl(baseUrl: String, params: Map<String, String>): String =
    kugouQueryUrl(baseUrl, params)
