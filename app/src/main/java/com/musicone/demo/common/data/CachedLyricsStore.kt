package com.musicone.demo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 歌词与音频共用账号隔离及磁盘配额，原文和翻译一起保存。 */
internal class CachedLyricsStore(private val context: Context) {
    private fun namespace(track: MusicTrack) = PlatformPreferences(context).readSession(track.source).cacheNamespace()
    private fun key(track: MusicTrack, namespace: String) = "lyrics|$namespace|${track.id}"

    suspend fun read(track: MusicTrack, namespace: String = namespace(track)): List<TimedLyric> = withContext(Dispatchers.IO) {
        val decoded = MusicDiskCache.get(context).read(key(track, namespace))?.let { decodeCachedLyrics(it.toString(Charsets.UTF_8)) }.orEmpty()
        val accepted = decoded.takeIf { hasReusableLyrics(track, it) }.orEmpty()
        if (decoded.isNotEmpty()) lyricDiagnostic(track, if (accepted.isEmpty()) "旧缓存待刷新" else "缓存命中", decoded)
        accepted
    }

    // 刷新失败或离线时仍可展示旧内容，但不能将它写成已验证的新版本。
    suspend fun readFallback(track: MusicTrack, namespace: String = namespace(track)): List<TimedLyric> = withContext(Dispatchers.IO) {
        MusicDiskCache.get(context).read(key(track, namespace))
            ?.let { decodeCachedLyrics(it.toString(Charsets.UTF_8)) }
            ?.takeIf(::validLyricTimeline).orEmpty()
    }

    suspend fun delete(track: MusicTrack, namespace: String = namespace(track)): Unit = withContext(Dispatchers.IO) {
        MusicDiskCache.get(context).delete(key(track, namespace))
    }

    suspend fun save(track: MusicTrack, lyrics: List<TimedLyric>, namespace: String = namespace(track)) = withContext(Dispatchers.IO) {
        if (hasReusableLyrics(track, lyrics)) MusicDiskCache.get(context).write(key(track, namespace), encodeCachedLyrics(lyrics).toByteArray())
    }
}
