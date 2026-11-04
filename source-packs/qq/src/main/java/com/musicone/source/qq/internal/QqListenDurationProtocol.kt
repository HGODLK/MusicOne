package com.musicone.source.qq.internal

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPOutputStream
import java.util.zip.InflaterInputStream

internal data class QqDurationDevice(val os: String, val model: String)

internal fun qqListenSessionAccountMatches(account: String, revision: Long, current: PlatformSession,
    currentRevision: Long): Boolean = account.isNotBlank() && revision == currentRevision &&
    qqCredentialAccountId(current.credential) == account

internal fun qqListenVkey(url: String): String = runCatching {
    URI(url).rawQuery.orEmpty().split('&').firstNotNullOfOrNull { pair ->
        val parts = pair.split('=', limit = 2)
        if (parts.size == 2 && parts[0].equals("vkey", ignoreCase = true))
            URLDecoder.decode(parts[1], "UTF-8").takeIf(String::isNotBlank) else null
    }.orEmpty()
}.getOrDefault("")

/** 统计秒数向下取整；毫秒尾数留在会话中，暂停后继续累积，不凭空补满一秒。 */
internal class QqListenDurationLedger {
    private var sequence = Long.MIN_VALUE
    private var playedMs = 0L
    private var attemptedMs = 0L
    fun update(nextSequence: Long, nextPlayedMs: Long): Boolean {
        if (nextSequence <= sequence || nextPlayedMs < playedMs) return false
        sequence = nextSequence
        playedMs = nextPlayedMs.coerceAtLeast(0L)
        return true
    }
    fun pendingMs(): Long = ((playedMs - attemptedMs).coerceAtLeast(0L) / 1_000L) * 1_000L
    // 网关没有幂等键，发出后即锁住片段；回执丢失不能重发并重复累加。
    fun markAttempted(durationMs: Long) {
        require(durationMs in 1L..pendingMs())
        attemptedMs += durationMs
    }
}

internal fun qqDurationComm(session: PlatformSession, device: QqDurationDevice): JSONObject {
    val comm = qqAndroidLoginComm(session.deviceId, false)
    val credentials = qqPlaybackComm(session.credential)
    credentials.keys().forEach { key -> comm.put(key, credentials.get(key)) }
    return comm.put("os_ver", device.os).put("phonetype", device.model)
}

internal fun qqListenDurationXml(
    session: PlatformSession,
    device: QqDurationDevice,
    anonymousUid: String,
    anonymousSid: String,
    track: MusicTrack,
    vkey: String,
    durationMs: Long,
    occurredAtMs: Long,
): String {
    val account = qqCredentialAccountId(session.credential)
    val songId = track.catalogId.toLongOrNull()?.takeIf { it > 0L }
        ?: throw PlatformApiException("QQ时长同步缺少数字歌曲标识")
    require(account.isNotBlank() && vkey.isNotBlank() && durationMs >= 1_000L)
    val seconds = durationMs / 1_000L
    val optime = occurredAtMs / 1_000L
    val timekey = MessageDigest.getInstance("MD5")
        .digest("$optime$seconds${account}gk2\$Lh-&l4#!4iow".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xff) }
    val comm = qqDurationComm(session, device).put("uid", anonymousUid).put("sid", anonymousSid)
    val values = linkedMapOf(
        "cmd" to "1", "optime" to optime.toString(), "QQ" to account, "uid" to anonymousUid,
        "os" to device.os, "model" to device.model, "version" to "14.9.0.8",
        "songtype" to "1", "playtype" to "5", "songid" to songId.toString(),
        "audiotime" to track.durationMs.coerceAtLeast(0L).toString(),
        "time" to seconds.toString(), "play_duration_mi" to durationMs.toString(),
        "timekey" to timekey, "vkey" to vkey, "co_singer" to track.artists,
    )
    return buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n<root>\r\n")
        comm.keys().forEach { key ->
            append('<').append(key).append('>').append(comm.optString(key).qqXmlEscaped())
                .append("</").append(key).append(">\r\n")
        }
        append("<cid>228</cid>\r\n<item")
        values.forEach { (name, value) -> append(' ').append(name).append("=\"").append(value.qqXmlEscaped()).append('"') }
        append("/>\r\n</root>")
    }
}

internal fun qqGzipXml(xml: String): ByteArray = ByteArrayOutputStream().use { bytes ->
    GZIPOutputStream(bytes).use { it.write(xml.toByteArray(Charsets.UTF_8)) }
    bytes.toByteArray()
}

internal fun requireQqListenDurationSuccess(response: PlatformHttpResponse) {
    if (response.status != 200) throw PlatformApiException("QQ时长上报失败（${response.status}）", response.status)
    val bytes = response.bytes
    val text = if (bytes.size > 5 && (0 until 5).all { bytes[it] == 0.toByte() }) {
        InflaterInputStream(bytes.inputStream(5, bytes.size - 5)).use { it.readBytes().toString(Charsets.UTF_8) }
    } else bytes.toString(Charsets.UTF_8)
    val json = runCatching { JSONObject(text) }.getOrNull()
        ?: throw PlatformApiException("QQ时长上报未返回有效回执")
    val code = json.optInt("code", -1)
    if (code != 0) throw PlatformApiException("QQ时长上报被拒绝（$code）", code)
}

private fun String.qqXmlEscaped(): String = replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
