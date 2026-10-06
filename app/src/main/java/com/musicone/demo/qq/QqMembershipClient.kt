package com.musicone.demo

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal data class QqMembership(val vip: Boolean, val superVip: Boolean = false, val validUntilMs: Long)

/** 读取账号实际会员权益，不通过任何歌曲播放地址推断身份。 */
internal class QqMembershipClient {
    fun query(credential: String): QqMembership? {
        if (qqCredentialAccountId(credential).isBlank() || qqCredentialMusicKey(credential).isBlank()) {
            return QqMembership(false, validUntilMs = System.currentTimeMillis() + QQ_ENTITLEMENT_TTL_MS)
        }
        return try {
            val response = PlatformHttp.postJson(
                "https://u.y.qq.com/cgi-bin/musicu.fcg", qqMembershipRequest(credential).toString(), credential,
                mapOf("Referer" to "https://y.qq.com/", "User-Agent" to "QQMusic 20050009(android 14)"),
                connectTimeoutMs = 5_000, readTimeoutMs = 8_000,
            )
            parseQqMembership(JSONObject(response.text))
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException || error is InterruptedException ||
                Thread.currentThread().isInterrupted || error.stopsPlaybackFallback()) throw error
            null
        }
    }
}

internal fun qqMembershipRequest(credential: String): JSONObject = JSONObject()
    .put("comm", qqPlaybackComm(credential).put("uin", qqCredentialAccountId(credential))
        .put("authst", qqCredentialMusicKey(credential)).put("ct", 11).put("cv", 20050009))
    .put("membership", JSONObject().put("module", "VipLogin.VipLoginInter")
        .put("method", "vip_login_base").put("param", JSONObject()))

internal fun parseQqMembership(response: JSONObject, nowMs: Long = System.currentTimeMillis()): QqMembership? {
    val request = response.optJSONObject("membership") ?: return null
    if (response.optInt("code", -1) != 0 || request.optInt("code", -1) != 0) return null
    val data = request.optJSONObject("data") ?: return null
    val identity = data.optJSONObject("identity") ?: return null
    if (!listOf("vip", "HugeVip", "eight", "twelve", "CPLoverFlag")
            .any { identity.qqAccessInt(it) in 0..1 } && data.qqAccessInt("svip") !in 0..1) return null
    val expiries = mutableListOf<Long>()
    fun active(flag: Int?, end: String): Boolean {
        if (flag != 1) return false
        if (end.isBlank()) return true
        val expiry = qqMembershipExpiry(end) ?: return false
        if (expiry <= nowMs) return false
        expiries += expiry
        return true
    }
    val superVip = active(identity.qqAccessInt("HugeVip"), identity.optString("HugeVipEnd"))
    val green = active(identity.qqAccessInt("vip"), identity.optString("overdate"))
    val svip = active(data.qqAccessInt("svip"), data.optString("send"))
    val eight = active(identity.qqAccessInt("eight"), identity.optString("eightEnd"))
    val twelve = active(identity.qqAccessInt("twelve"), identity.optString("twelveEnd"))
    val couple = active(identity.qqAccessInt("CPLoverFlag"), identity.optString("CPLoverEnd"))
    return QqMembership(green || svip || superVip || eight || twelve || couple, superVip,
        minOf(nowMs + QQ_ENTITLEMENT_TTL_MS, expiries.minOrNull() ?: Long.MAX_VALUE))
}

private fun qqMembershipExpiry(raw: String): Long? {
    val value = raw.trim()
    if (value.length in 10..13 && value.all(Char::isDigit)) {
        return value.toLongOrNull()?.let { if (value.length == 10) it * 1_000L else it }
    }
    val formats = listOf("yyyy-MM-dd HH:mm:ss", "yyyyMMddHHmmss", "yyyy-MM-dd", "yyyyMMdd")
    for (pattern in formats) {
        val parser = SimpleDateFormat(pattern, Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            isLenient = false
        }
        val position = java.text.ParsePosition(0)
        val date = parser.parse(value, position) ?: continue
        if (position.index == value.length) return date.time + if (pattern.length <= 10) 86_400_000L else 0L
    }
    return null
}
