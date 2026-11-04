package com.musicone.source.qq.internal

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

internal enum class QqRequestOrigin(val diagnosticName: String) {
    PLAYBACK("用户点播"),
    BATCH_CACHE("批量缓存"),
    PRELOAD("下一首预取"),
    COMPATIBILITY("兼容取票"),
    QUALITY_PROBE("音质探测"),
    ACCESS_PROBE("权限探测"),
    VIP_PROBE("会员探测"),
    OTHER("其他请求"),
}

internal open class QqSessionRequestException(
    message: String,
    apiCode: Int? = null,
    val diagnostic: String,
) : PlatformApiException(message, apiCode)

internal class QqCredentialExpiredException(code: Int, diagnostic: String) :
    QqSessionRequestException("QQ 音乐登录状态已失效，请重新登录", code, diagnostic)

internal class QqRequestRejectedException(code: Int, diagnostic: String) : QqSessionRequestException(
    "QQ 音乐请求被拒绝（$code），请在 Musicss 重新登录后重试", code, diagnostic,
)

internal class QqPlaybackItemUnavailableException(
    message: String,
    apiCode: Int? = null,
    val diagnostic: String,
) : PlatformApiException(message, apiCode)

internal fun Throwable.stopsPlaybackFallback(): Boolean =
    this is PlatformSecurityVerificationRequired ||
        this is QqSessionRequestException ||
        (this is PlatformApiException && apiCode in setOf(301, 401, 403, 429))

/** 服务端明确拒绝登录票据；网络故障和风控不算。 */
internal fun Throwable.rejectsQqCredential(): Boolean =
    this is PlatformApiException && (apiCode == 301 || apiCode == 401 || apiCode in QQ_CREDENTIAL_EXPIRED_CODES)

internal fun requireQqMusicUSuccess(
    response: JSONObject,
    cookie: String,
    origin: QqRequestOrigin,
): JSONObject {
    val topCode = response.optInt("code", 0)
    if (topCode != 0) throw response.qqBusinessFailure(topCode, cookie, origin, "顶层")
    val keys = response.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        if (key == "comm") continue
        val child = response.optJSONObject(key) ?: continue
        if (!child.has("code")) continue
        val code = child.optInt("code", 0)
        if (code != 0) throw child.qqBusinessFailure(code, cookie, origin, key)
    }
    return response
}

internal fun JSONObject.throwIfQqPlaybackItemsFailed(
    cookie: String,
    origin: QqRequestOrigin,
) {
    val infos = optJSONArray("midurlinfo") ?: return
    val failed = buildList {
        for (index in 0 until infos.length()) {
            val item = infos.optJSONObject(index) ?: continue
            val code = item.optInt("result", 0)
            if (code != 0) add(item to code)
        }
    }
    if (failed.isEmpty()) return
    failed.firstOrNull { (_, code) -> code in QQ_SESSION_ERROR_CODES }
        ?.let { (item, code) ->
            val failure = item.takeIf { it.qqVerificationField(code) != null } ?: this
            throw failure.qqBusinessFailure(code, cookie, origin, "midurlinfo")
        }
    val codes = failed.map { it.second }.distinct().joinToString(",")
    val diagnostic = "来源=${origin.diagnosticName}，层级=midurlinfo，业务码=$codes，有地址=${qqHasPlaybackUrl(infos)}"
    qqDiagnostic(diagnostic)
    if (qqHasPlaybackUrl(infos)) return
    throw QqPlaybackItemUnavailableException("QQ 音乐未返回当前歌曲或音质的播放地址", failed.first().second, diagnostic)
}

private fun JSONObject.qqBusinessFailure(
    code: Int,
    cookie: String,
    origin: QqRequestOrigin,
    level: String,
): Exception {
    val verificationField = qqVerificationField(code)
    val trustedUrl = verificationField?.second?.trustedQqVerificationUrl()
        ?.let { if (code == QQ_VKEY_SECURITY_CRACKDOWN_CODE) it.withQqVkeyVerificationFlag() else it }
        .orEmpty()
    val rawMessage = optString("message").ifBlank {
        optString("msg").ifBlank { optJSONObject("data")?.optString("msg").orEmpty() }
    }
    val invalidContext = rawMessage.substringAfterLast(';').trim().equals("invalid", ignoreCase = true)
    val diagnostic = "来源=${origin.diagnosticName}，层级=$level，业务码=$code，验证字段=${verificationField?.first ?: "无"}，提示invalid=$invalidContext"
    qqDiagnostic(diagnostic)
    // 错误码与对应的可信入口必须同时存在；解析错误本身不改变播放会话。
    if (trustedUrl.isNotBlank()) {
        val challenge = PlatformSecurityChallenge(
            source = MusicSource.QQ,
            kind = PlatformSecurityVerificationKind.WEB,
            title = "QQ 音乐安全验证",
            message = "请按 QQ 音乐页面提示确认本次请求",
            url = trustedUrl,
            credentialSeed = cookie,
        )
        return PlatformSecurityVerificationRequired(challenge)
    }
    if (code in QQ_CREDENTIAL_EXPIRED_CODES) return QqCredentialExpiredException(code, diagnostic)
    if (code == QQ_VKEY_SECURITY_CRACKDOWN_CODE) return QqRequestRejectedException(code, diagnostic)
    val message = if (invalidContext) "QQ 音乐请求失败（$code）"
        else rawMessage.ifBlank { "QQ 音乐请求失败（$code）" }
    return QqSessionRequestException(message, code, diagnostic)
}

private fun JSONObject.qqVerificationField(code: Int): Pair<String, String>? {
    val names = when (code) {
        QQ_RISK_CONTROL_CODE -> listOf("feedbackURL", "feedbackUrl")
        QQ_VKEY_SECURITY_CRACKDOWN_CODE -> listOf("validURL", "validUrl")
        else -> return null
    }
    names.forEach { name ->
        (opt(name) as? String)?.trustedQqVerificationUrl()?.let { return name to it }
    }
    val data = optJSONObject("data")
    if (data != null && data !== this) data.qqVerificationField(code)?.let { return it }
    return null
}

private fun String.trustedQqVerificationUrl(): String? = runCatching {
    val uri = URI.create(this)
    val host = uri.host.orEmpty().lowercase()
    takeIf {
        uri.scheme.equals("https", ignoreCase = true) && QQ_VERIFICATION_HOST_SUFFIXES.any { suffix ->
            host == suffix || host.endsWith(".$suffix")
        }
    }
}.getOrNull()

private fun String.withQqVkeyVerificationFlag(): String {
    val fragmentIndex = indexOf('#')
    val base = if (fragmentIndex >= 0) substring(0, fragmentIndex) else this
    val fragment = if (fragmentIndex >= 0) substring(fragmentIndex) else ""
    if (Regex("(?:^|[?&])_vkey_verify=1(?:&|$)").containsMatchIn(base)) return this
    val separator = when {
        base.endsWith('?') || base.endsWith('&') -> ""
        '?' in base -> "&"
        else -> "?"
    }
    return "$base${separator}_vkey_verify=1$fragment"
}

private fun qqHasPlaybackUrl(infos: JSONArray): Boolean {
    for (index in 0 until infos.length()) {
        val item = infos.optJSONObject(index) ?: continue
        if (item.optString("purl").isNotBlank() || item.optString("wifiurl").isNotBlank()) return true
    }
    return false
}

internal fun qqDiagnostic(message: String) {
    runCatching { Log.w(QQ_DIAGNOSTIC_TAG, message) }
}

private const val QQ_DIAGNOSTIC_TAG = "QqPlayback"
private const val QQ_RISK_CONTROL_CODE = 2001
private const val QQ_VKEY_SECURITY_CRACKDOWN_CODE = 104009
private val QQ_CREDENTIAL_EXPIRED_CODES = setOf(1000, 104400, 104401)
private val QQ_SESSION_ERROR_CODES = QQ_CREDENTIAL_EXPIRED_CODES + QQ_RISK_CONTROL_CODE + QQ_VKEY_SECURITY_CRACKDOWN_CODE
private val QQ_VERIFICATION_HOST_SUFFIXES = setOf("qq.com", "tencent.com", "qcloud.com")
