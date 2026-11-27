package com.musicone

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 歌词缓存按账号隔离并计入磁盘配额，原文和翻译存在一起。 */
internal class CachedLyricsStore(private val context: Context) {
    private fun namespace(track: MusicTrack) = com.musicone.sourceintegration.sourceCacheNamespace(context, track.source)
    private fun key(track: MusicTrack, namespace: String) = "lyrics|$namespace|${track.id}"

    suspend fun read(track: MusicTrack, namespace: String = namespace(track)): List<TimedLyric> = withContext(Dispatchers.IO) {
        val decoded = MusicDiskCache.get(context).read(key(track, namespace))?.let { decodeCachedLyrics(it.toString(Charsets.UTF_8)) }.orEmpty()
        val accepted = decoded.takeIf { hasReusableLyrics(track, it) }.orEmpty()
        if (decoded.isNotEmpty()) lyricDiagnostic(track, if (accepted.isEmpty()) "旧缓存待刷新" else "缓存命中", decoded)
        accepted
    }

    // 离线或刷新失败时先拿旧歌词兜底显示，但不把它当成已校验的新版本覆盖回去。
    suspend fun readFallback(track: MusicTrack, namespace: String = namespace(track)): List<TimedLyric> = withContext(Dispatchers.IO) {
        MusicDiskCache.get(context).read(key(track, namespace))
            ?.let { decodeCachedLyrics(it.toString(Charsets.UTF_8)) }
            ?.takeIf(::validLyricTimeline).orEmpty()
    }

    suspend fun delete(track: MusicTrack, namespace: String = namespace(track)): Unit = withContext(Dispatchers.IO) {
        MusicDiskCache.get(context).delete(key(track, namespace))
    }

    suspend fun save(track: MusicTrack, lyrics: List<TimedLyric>, namespace: String = namespace(track)) = withContext(Dispatchers.IO) {
        val cache = MusicDiskCache.get(context)
        if (!hasReusableLyrics(track, lyrics)) {
            if (lyrics.isEmpty() || !validLyricTimeline(lyrics)) return@withContext
            // 降级接口拿到的歌词只当离线兜底，不覆盖本地已有的高质量歌词缓存。
            val existing = cache.read(key(track, namespace))?.let { decodeCachedLyrics(it.toString(Charsets.UTF_8)) }.orEmpty()
            if (hasReusableLyrics(track, existing) ||
                (existing.any { it.words.isNotEmpty() } &&
                    lyrics.none { it.words.isNotEmpty() })) return@withContext
        }
        cache.write(key(track, namespace), encodeCachedLyrics(lyrics).toByteArray())
    }
}
