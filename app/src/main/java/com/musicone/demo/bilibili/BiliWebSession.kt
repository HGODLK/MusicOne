package com.musicone.demo

import android.content.Context
import java.util.UUID

/** 设备 Cookie 与账号 Cookie 独立保存，所有网页请求统一合并。 */
internal class BiliWebSession(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("musicone_bili_device", Context.MODE_PRIVATE)

    fun headers(cookie: String, referer: String = "https://www.bilibili.com/"): Map<String, String> = synchronized(lock) {
        val buvid = preferences.getString("buvid3", null)?.takeIf(String::isNotBlank)
            ?: UUID.randomUUID().toString().replace("-", "").uppercase().also {
                preferences.edit().putString("buvid3", it).apply()
            }
        mapOf("User-Agent" to BiliAuthApi.DESKTOP_UA, "Referer" to referer,
            "Origin" to "https://www.bilibili.com", "Accept-Language" to "zh-CN,zh;q=0.9",
            "Cookie" to mergeBiliCookies("buvid3=$buvid", cookie))
    }

    companion object { private val lock = Any() }
}

internal fun parseBiliCookies(value: String): Map<String, String> = value.split(';').mapNotNull {
    val pair = it.trim().split('=', limit = 2)
    if (pair.size == 2 && pair[0].isNotBlank() && pair[1].isNotBlank()) pair[0] to pair[1] else null
}.toMap()

internal fun mergeBiliCookies(device: String, account: String): String =
    (parseBiliCookies(device) + parseBiliCookies(account)).entries.joinToString("; ") { "${it.key}=${it.value}" }
