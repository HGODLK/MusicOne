package com.musicone.demo

import android.content.Context
import androidx.media3.datasource.cache.ContentMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 播放索引与音频数据分离；完整性始终根据磁盘区间重新判断。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class OfflineMusicStore(private val context: Context) {
    private val store = context.getSharedPreferences("offline_music_index", Context.MODE_PRIVATE)
    private val preferences = PlatformPreferences(context)
    private fun prefix(track: MusicTrack) = "${preferences.readSession(track.source).cacheNamespace()}|${track.id}|"

    fun remember(resolved: ResolvedPlayback, preferred: AudioQuality = resolved.source.requestedQuality) {
        if (resolved.source.trial) return
        val track = resolved.track
        val key = playbackCacheKey(context, track)
        store.edit().putString(key, track.toQqStoredTrackJson()
            .put("url", resolved.source.url).put("quality", resolved.source.actualQuality.name)
            .put("preferredQuality", preferred.name)
            .put("pending", resolved.source.verificationPending).toString()).apply()
    }

    fun hasNetwork(): Boolean = context.hasMusicNetwork()

    private fun record(key: String): JSONObject? = store.getString(key, null)
        ?.let { runCatching { JSONObject(it) }.getOrNull() }

    // 只在 IO 线程调用；未完成播放前校验的整首缓存也能在断网后补验。
    fun verifiedComplete(key: String): Boolean {
        val record = record(key)
        return verifyOfflineRecord(record, completeBytes(key), {
            cachedAudioDuration(MusicDiskCache.get(context).audio, key, offlinePlaybackUrl(key))
        }, { full -> verify(key, full) })
    }

    fun availableTrackIds(): Set<String> {
        val namespaces = MusicSource.entries.map { preferences.readSession(it).cacheNamespace() }.toSet()
        return MusicDiskCache.get(context).audio.keys.asSequence()
            .filter { it.substringBefore('|') in namespaces }
            .filter { offlineRecordQuality(it, record(it)) != null && verifiedComplete(it) }
            .map { it.substringAfter('|').substringBefore('|') }.toSet()
    }

    fun complete(key: String): Boolean {
        val record = store.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (record?.optBoolean("pending") == true || record?.optBoolean("trial") == true) return false
        return completeBytes(key)
    }

    fun completeBytes(key: String): Boolean {
        val cache = MusicDiskCache.get(context).audio
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return length > 0 && cache.isCached(key, 0, length)
    }

    fun verify(key: String, full: Boolean) {
        val raw = store.getString(key, null) ?: return
        val record = runCatching { JSONObject(raw) }.getOrNull() ?: return
        store.edit().putString(key, record.put("pending", false).put("trial", !full).toString()).apply()
    }

    fun keys(track: MusicTrack): List<String> = MusicDiskCache.get(context).audio.keys.filter { it.startsWith(prefix(track)) }

    fun status(track: MusicTrack): String {
        val keys = keys(track)
        return when {
            keys.any(::complete) -> "已缓存 · 可离线播放"
            keys.isNotEmpty() -> "部分缓存"
            else -> "未缓存"
        }
    }

    suspend fun resolve(track: MusicTrack, preferred: AudioQuality, allowOtherQuality: Boolean): ResolvedPlayback? = withContext(Dispatchers.IO) {
        val candidates = keys(track).mapNotNull { key ->
            val saved = record(key)
            val url = offlinePlaybackUrl(key)
            val quality = offlineRecordQuality(key, saved) ?: return@mapNotNull null
            if ((!allowOtherQuality && quality != preferred) || !verifiedComplete(key)) return@mapNotNull null
            val restored = saved?.toQqStoredTrack() ?: track
            ResolvedPlayback(restored.copy(previewUrl = url, lyrics = track.lyrics), PlaybackSource(url, preferred, quality,
                quality.bitRate, "", false))
        }
        candidates.firstOrNull { it.source.actualQuality == preferred }
            ?: candidates.maxByOrNull { it.source.actualQuality.ordinal }
    }

    fun remove(track: MusicTrack) {
        keys(track).forEach { key ->
            MusicDiskCache.get(context).removeAudio(key)
            store.edit().remove(key).apply()
        }
    }

    fun savedTracks(source: MusicSource): List<MusicTrack> {
        val namespace = preferences.readSession(source).cacheNamespace() + "|"
        return store.all.filterKeys { it.startsWith(namespace) }.values.mapNotNull { raw ->
            runCatching { JSONObject(raw as String).toQqStoredTrack() }.getOrNull()
        }.distinctBy { it.id }
    }
}
