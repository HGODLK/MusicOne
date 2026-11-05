package com.musicone.source.subsonic.internal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 每日推荐卡片的内容：每台服务器每天第一次请求时随机抽一批歌并保存，
 * 当天再打开都是同一批，第二天换新。Subsonic 本身没有每日推荐。
 */
internal class SubsonicDailyStore(context: Context, private val today: () -> String = ::localDate) {
    private val preferences = context.getSharedPreferences("subsonic_daily", Context.MODE_PRIVATE)

    fun read(serverKey: String): List<SubsonicSong>? {
        val value = preferences.getString(serverKey, null) ?: return null
        val json = runCatching { JSONObject(value) }.getOrNull() ?: return null
        if (json.optString("date") != today()) return null
        val songs = json.optJSONArray("songs") ?: return null
        return (0 until songs.length()).mapNotNull { songs.optJSONObject(it)?.song() }.takeIf { it.isNotEmpty() }
    }

    fun save(serverKey: String, songs: List<SubsonicSong>) {
        if (songs.isEmpty()) return
        preferences.edit().putString(serverKey, JSONObject().put("date", today())
            .put("songs", JSONArray(songs.map { it.raw })).toString()).apply()
    }
}

private fun localDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
