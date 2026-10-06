package com.musicone.demo

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 每日歌单在同一账号、同一自然日内保持固定。 */
internal class QqDailyMixStore(context: Context) {
    private val preferences = context.getSharedPreferences("qq_daily_mix", Context.MODE_PRIVATE)

    fun read(accountId: String): List<MusicTrack>? {
        if (preferences.getInt(KEY_VERSION, 0) != CACHE_VERSION ||
            preferences.getString(KEY_DATE, null) != today() ||
            preferences.getString(KEY_ACCOUNT, null) != accountId
        ) return null
        val raw = preferences.getString(KEY_TRACKS, null) ?: return null
        return runCatching {
            val values = JSONArray(raw)
            buildList {
                for (index in 0 until values.length()) {
                    values.optJSONObject(index)?.toQqStoredTrack()?.let(::add)
                }
            }.takeIf(List<MusicTrack>::isNotEmpty)
        }.getOrNull()
    }

    fun save(accountId: String, tracks: List<MusicTrack>) {
        if (tracks.isEmpty()) return
        val values = JSONArray().apply { tracks.take(30).forEach { put(it.toQqStoredTrackJson()) } }
        preferences.edit {
            putInt(KEY_VERSION, CACHE_VERSION)
            putString(KEY_DATE, today())
            putString(KEY_ACCOUNT, accountId)
            putString(KEY_TRACKS, values.toString())
        }
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())

    private companion object {
        const val CACHE_VERSION = 3
        const val KEY_VERSION = "version"
        const val KEY_DATE = "date"
        const val KEY_ACCOUNT = "account"
        const val KEY_TRACKS = "tracks"
    }
}
