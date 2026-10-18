package com.musicone.demo

import android.util.Log
import org.json.JSONObject
import java.net.URI

internal fun kugouMediaHeaders(url: String, cookie: String): Map<String, String> = buildMap {
    putAll(kugouPcHeaders())
    val uri = runCatching { URI.create(url) }.getOrNull()
    // 旧 HTTP 媒体地址仅携带媒体票据，账号 Cookie 只发送给平台自己的 HTTPS 域名。
    if (cookie.isNotBlank() && uri?.scheme.equals("https", ignoreCase = true) &&
        uri?.host.orEmpty().isKugouMediaHost()) put("Cookie", cookie)
}

internal fun PlaybackSource.withKugouMediaHeaders(cookie: String): PlaybackSource =
    copy(requestHeaders = kugouMediaHeaders(url, cookie))

internal fun kugouPlaybackApiError(response: JSONObject, message: String): PlatformApiException {
    val data = response.optJSONObject("data")
    val code = response.optInt("error_code", response.optInt("errcode",
        data?.let { it.optInt("error_code", it.optInt("errcode", 0)) } ?: 0)).takeIf { it != 0 }
    if (code in setOf(301, 401, 403, 429, 20_028, 34_351)) {
        return KugouPlaybackBlocked(if (code == 20_028 || code == 34_351)
            "酷狗音乐需要账号安全验证，请在官方客户端完成后重试" else "酷狗音乐请求受限，请检查登录状态后重试", code)
    }
    if (response.optInt("status", -1) == 3 && code == null) return TrackUnavailableException("歌曲暂无版权")
    return KugouPlaybackFailure(KugouPlaybackFailureReason.SERVER_NO_URL,
        code?.let { "$message（$it）" } ?: message, code)
}

internal enum class KugouPlaybackFailureReason { SERVER_NO_URL, URL_REJECTED, QUALITY_MISMATCH, MEDIA_PROBE, RESOURCE_MISSING }

internal class KugouPlaybackFailure(val reason: KugouPlaybackFailureReason, message: String, code: Int? = null) :
    PlatformApiException(message, code)

internal class KugouPlaybackBlocked(message: String, code: Int?) : PlatformApiException(message, code)

internal fun kugouPreferredPlaybackFailure(previous: Exception?, current: Exception): Exception =
    // 备用接口笼统的无地址结果不能覆盖前面已明确的地址兼容、档位或媒体错误。
    if (previous is KugouPlaybackFailure && current is KugouPlaybackFailure &&
        previous.reason != KugouPlaybackFailureReason.SERVER_NO_URL &&
        current.reason == KugouPlaybackFailureReason.SERVER_NO_URL) previous else current

internal fun kugouMaySkipFailedTrack(error: Throwable): Boolean = error is TrackUnavailableException

internal fun JSONObject.kugouHasRawPlaybackUrl(): Boolean = listOf(
    "url", "play_url", "tracker_url", "backup_url", "play_backup_url", "backupUrl", "mp3Url", "backupMp3Url",
).any { name ->
    when (val value = opt(name)) {
        is String -> value.startsWith("http", ignoreCase = true)
        is org.json.JSONArray -> (0 until value.length()).any { value.optString(it).startsWith("http", ignoreCase = true) }
        else -> false
    }
} || listOf("data", "info", "128", "320", "flac", "high").any { optJSONObject(it)?.kugouHasRawPlaybackUrl() == true } ||
    listOf("data", "relate_goods").any { name ->
        val items = optJSONArray(name)
        items != null && (0 until items.length()).any { items.optJSONObject(it)?.kugouHasRawPlaybackUrl() == true }
    }

internal fun JSONObject.requireKugouPlaybackResponse() {
    val errorCode = optInt("error_code", optInt("errcode", 0))
    if (errorCode != 0 || optInt("status", 1) != 1) {
        throw kugouPlaybackApiError(this, "酷狗音乐取址失败")
    }
}

internal fun Throwable.kugouPlaybackMessage(): String = when (this) {
    is java.io.IOException -> "酷狗音乐连接失败，请检查网络后重试"
    else -> asUserMessage()
}

internal fun kugouMissingPlaybackUrl(response: JSONObject, message: String): PlatformApiException =
    if (response.kugouHasRawPlaybackUrl()) KugouPlaybackFailure(KugouPlaybackFailureReason.URL_REJECTED, "酷狗音乐返回的播放地址暂不兼容")
    else kugouPlaybackApiError(response, message)

internal fun kugouPlaybackFailure(stage: String, quality: AudioQuality, error: Exception) {
    // 只记录协议阶段、档位、异常类型与业务码，禁止记录带票据的 URL 和异常正文。
    Log.w("KugouPlayback", "stage=$stage quality=${quality.name} error=${error.javaClass.simpleName} " +
        "code=${(error as? PlatformApiException)?.apiCode ?: 0} " +
        "reason=${(error as? KugouPlaybackFailure)?.reason?.name ?: if (error is java.io.IOException) "NETWORK" else "OTHER"}")
}
