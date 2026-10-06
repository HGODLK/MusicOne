package com.musicone.demo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 歌词与音频共用账号隔离及磁盘配额，原文和翻译一起保存。 */
internal class CachedLyricsStore(private val context: Context) {
    private fun namespace(track: MusicTrack) = PlatformPreferences(context).readSession(track.source).cacheNamespace()
    private fun key(track: MusicTrack, namespace: String) = "lyrics|$namespace|${track.id}"

    suspend fun read(track: MusicTrack, namespace: String = namespace(track)): List<TimedLyric> = withContext(Dispatchers.IO) {
        MusicDiskCache.get(context).read(key(track, namespace))?.let { decodeCachedLyrics(it.toString(Charsets.UTF_8)) }.orEmpty()
    }

    suspend fun save(track: MusicTrack, lyrics: List<TimedLyric>, namespace: String = namespace(track)) = withContext(Dispatchers.IO) {
        if (lyrics.isNotEmpty()) MusicDiskCache.get(context).write(key(track, namespace), encodeCachedLyrics(lyrics).toByteArray())
    }
}

internal fun encodeCachedLyrics(lyrics: List<TimedLyric>): String = JSONArray().apply {
    lyrics.forEach { line -> put(JSONObject().put("time", line.timeMs).put("text", line.text)
        .apply { line.translation?.let { put("translation", it) } }) }
}.toString()

internal fun decodeCachedLyrics(raw: String): List<TimedLyric> = runCatching {
    JSONArray(raw).searchObjects().map { value ->
        TimedLyric(value.getLong("time"), value.getString("text"),
            value.optString("translation").takeIf { it.isNotBlank() })
    }
}.getOrDefault(emptyList())
