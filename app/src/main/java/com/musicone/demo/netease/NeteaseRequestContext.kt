package com.musicone.demo

import android.content.res.Resources
import android.os.Build
import org.json.JSONObject
import java.util.UUID

/** 与 api-enhanced 的 EAPI 保持一致：header 是对象，登录凭据也进入加密上下文。 */
internal enum class NeteaseRequestProfile { PC, ANDROID }

internal fun neteaseRequestHeader(
    cookie: String,
    deviceId: String,
    profile: NeteaseRequestProfile = NeteaseRequestProfile.PC,
): JSONObject {
    val cookies = cookie.split(';').mapNotNull { part ->
        val pair = part.trim().split('=', limit = 2)
        if (pair.size == 2) pair[0] to pair[1] else null
    }.toMap()
    val header = when (profile) {
        NeteaseRequestProfile.PC -> JSONObject()
            .put("os", "pc").put("osver", "Microsoft-Windows-10-Professional-build-19045-64bit")
            .put("appver", "3.0.18.203152").put("versioncode", "203152").put("buildver", "203152")
            .put("resolution", "1920x1080").put("channel", "netease")
        NeteaseRequestProfile.ANDROID -> JSONObject()
            .put("os", "android").put("osver", Build.VERSION.RELEASE)
            .put("appver", ANDROID_APP_VERSION).put("versioncode", ANDROID_VERSION_CODE)
            .put("buildver", (System.currentTimeMillis() / 1_000L).toString())
            .put("resolution", androidResolution()).put("mobilename", Build.MODEL)
            .put("channel", "netease")
    }
    return header.put("deviceId", deviceId)
        .put("requestId", "${System.currentTimeMillis()}_${UUID.randomUUID()}")
        .put("__csrf", cookies["__csrf"].orEmpty()).apply {
            listOf("MUSIC_U", "MUSIC_A").forEach { name -> cookies[name]?.let { put(name, it) } }
        }
}

internal fun neteaseUserAgent(profile: NeteaseRequestProfile): String = when (profile) {
    NeteaseRequestProfile.PC -> NETEASE_PC_USER_AGENT
    NeteaseRequestProfile.ANDROID -> "NeteaseMusic/$ANDROID_APP_VERSION($ANDROID_VERSION_CODE);" +
        "Dalvik/2.1.0 (Linux; U; Android ${Build.VERSION.RELEASE}; ${Build.MODEL} Build/${Build.ID})"
}

private fun androidResolution(): String = runCatching {
    val metrics = Resources.getSystem().displayMetrics
    "${metrics.widthPixels}x${metrics.heightPixels}"
}.getOrDefault("1080x2400")

private const val ANDROID_APP_VERSION = "9.6.08"
private const val ANDROID_VERSION_CODE = "9006008"

internal const val NETEASE_PC_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
    "AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36 NeteaseMusicDesktop/3.0.18.203152"
