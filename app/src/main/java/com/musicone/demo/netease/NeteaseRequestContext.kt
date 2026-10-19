package com.musicone.demo

import org.json.JSONObject
import java.util.UUID

/** 与 api-enhanced 的 EAPI 保持一致：header 是对象，登录凭据也进入加密上下文。 */
internal fun neteaseRequestHeader(cookie: String, deviceId: String): JSONObject {
    val cookies = cookie.split(';').mapNotNull { part ->
        val pair = part.trim().split('=', limit = 2)
        if (pair.size == 2) pair[0] to pair[1] else null
    }.toMap()
    return JSONObject().put("os", "pc").put("osver", "Microsoft-Windows-10-Professional-build-19045-64bit")
        .put("appver", "3.0.18.203152").put("deviceId", deviceId)
        .put("versioncode", "203152").put("buildver", "203152")
        .put("resolution", "1920x1080").put("channel", "netease")
        .put("requestId", "${System.currentTimeMillis()}_${UUID.randomUUID()}")
        .put("__csrf", cookies["__csrf"].orEmpty()).apply {
            listOf("MUSIC_U", "MUSIC_A").forEach { name -> cookies[name]?.let { put(name, it) } }
        }
}

internal const val NETEASE_PC_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
    "AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36 NeteaseMusicDesktop/3.0.18.203152"
