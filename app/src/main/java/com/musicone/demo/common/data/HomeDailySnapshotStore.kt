package com.musicone.demo

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal data class HomeDailySnapshot(val refreshedDay: String, val payload: String)

/** 首页内容与成功日期一起保存，账号之间隔离，读取缓存不会延长有效期。 */
internal class HomeDailySnapshotStore(cache: MusicDiskCache, namespace: String, section: String) {
    private val cache = cache
    private val key = "home-daily:v1:$namespace:$section"

    fun read(): HomeDailySnapshot? = cache.read(key)?.let {
        decodeHomeDailySnapshot(String(it, Charsets.UTF_8))
    }

    fun write(snapshot: HomeDailySnapshot) {
        cache.write(key, encodeHomeDailySnapshot(snapshot).toByteArray(Charsets.UTF_8))
    }
}

internal fun homeRefreshDay(now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = zone }.format(Date(now))

internal fun homeDailyRefreshDue(force: Boolean, hasContent: Boolean, refreshedDay: String?, today: String): Boolean =
    force || !hasContent || refreshedDay != today

internal fun encodeHomeDailySnapshot(snapshot: HomeDailySnapshot): String = JSONObject()
    .put("day", snapshot.refreshedDay).put("payload", snapshot.payload).toString()

internal fun decodeHomeDailySnapshot(raw: String): HomeDailySnapshot? = runCatching {
    val value = JSONObject(raw)
    HomeDailySnapshot(value.getString("day"), value.getString("payload"))
}.getOrNull()
