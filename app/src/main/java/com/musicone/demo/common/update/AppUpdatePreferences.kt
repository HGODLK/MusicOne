package com.musicone.demo

import android.content.Context
import java.net.URLDecoder
import org.json.JSONObject

internal class AppUpdatePreferences(context: Context) {
    private val store = context.applicationContext.getSharedPreferences("app_updates", Context.MODE_PRIVATE)

    fun lastShownAt(): Long? = if (store.contains("last_shown_at")) store.getLong("last_shown_at", 0) else null

    fun markShown(now: Long) {
        // 所有版本共用一个展示时间，发布更高版本也不能绕过三天间隔。
        store.edit().putLong("last_shown_at", now).apply()
    }

    fun latest(): MusicOneRelease? = runCatching {
        val raw = store.getString("latest", null) ?: return@runCatching null
        val value = JSONObject(raw)
        val version = MusicOneVersion.parse(value.getString("version")) ?: return@runCatching null
        val url = value.getString("download_url")
        if (!url.startsWith(MUSIC_ONE_DOWNLOAD_ROOT)) return@runCatching null
        val decodedUrl = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        val fileName = decodedUrl.substringAfterLast('/')
        val prefix = "MusicOne-${version.coreLabel}"
        val validName = fileName == "$prefix.apk" ||
            (fileName.startsWith("$prefix-") && fileName.endsWith(".apk") && !fileName.endsWith("-debugrelease.apk"))
        if (!validName) return@runCatching null
        val body = value.optString("body")
        MusicOneRelease(version, url, body)
    }.getOrNull()

    fun saveLatest(release: MusicOneRelease?) {
        val editor = store.edit()
        if (release == null) editor.remove("latest") else editor.putString("latest", JSONObject()
            .put("version", release.version.label)
            .put("download_url", release.downloadUrl)
            .put("body", release.body).toString())
        editor.apply()
    }
}
