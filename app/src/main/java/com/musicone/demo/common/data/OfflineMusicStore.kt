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
    private val supplemental = SupplementalOfflineMusicStore(context)
    private fun prefix(track: MusicTrack) = "${preferences.readSession(track.source).cacheNamespace()}|${track.id}|"

    fun remember(resolved: ResolvedPlayback, preferred: AudioQuality = resolved.source.requestedQuality) {
        if (resolved.track.source == MusicSource.BILIBILI) { supplemental.remember(resolved); return }
        if (resolved.source.trial) return
        val track = resolved.track
        val key = playbackCacheKey(context, track)
        MusicDiskCache.get(context).trust(key, !resolved.source.verificationPending)
        val reference = prefix(track) + cacheResourceVariant(key)
        store.edit().putString(reference, track.toQqStoredTrackJson().put("cacheKey", key)
            .put("url", resolved.source.url).put("quality", resolved.source.actualQuality.name)
            .put("preferredQuality", preferred.name)
            .put("pending", resolved.source.verificationPending).toString()).apply()
    }

    fun hasNetwork(): Boolean = context.hasMusicNetwork()

    private fun records(source: MusicSource? = null): List<Pair<String, JSONObject>> {
        val namespaces = (source?.let(::listOf) ?: MusicSource.entries).map { preferences.readSession(it).cacheNamespace() }.toSet()
        return store.all.mapNotNull { (key, value) ->
            if (key.substringBefore('|') !in namespaces) return@mapNotNull null
            runCatching { JSONObject(value as String) }.getOrNull()?.let { key to it }
        }
    }

    private fun physical(reference: String, record: JSONObject) = record.optString("cacheKey", reference)
    private fun record(key: String): JSONObject? = records().firstOrNull { physical(it.first, it.second) == key }?.second

    // 只在 IO 线程调用；未完成播放前校验的整首缓存也能在断网后补验。
    fun verifiedComplete(key: String): Boolean {
        val record = record(key)
        return verifyOfflineRecord(record, completeBytes(key), {
            cachedAudioDuration(MusicDiskCache.get(context).audio, key, offlinePlaybackUrl(key))
        }, { full -> verify(key, full) })
    }

    fun availableTrackIds(): Set<String> = playbackTrace("MusicOne:offline-index-scan") {
        records().filter { (reference, row) ->
            val key = physical(reference, row)
            offlineRecordQuality(key, row) != null && verifiedComplete(key)
        }.mapTo(hashSetOf()) { it.second.optString("id") } + supplemental.completeTrackIds()
    }

    fun complete(key: String): Boolean {
        val record = record(key)
        if (record?.optBoolean("pending") == true || record?.optBoolean("trial") == true) return false
        return completeBytes(key)
    }

    fun completeBytes(key: String): Boolean {
        val cache = MusicDiskCache.get(context).audio
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return length > 0 && cache.isCached(key, 0, length)
    }

    fun verify(key: String, full: Boolean) {
        val edit = store.edit()
        records().filter { physical(it.first, it.second) == key }.forEach { (reference, row) ->
            edit.putString(reference, row.put("pending", false).put("trial", !full).toString())
        }
        edit.apply()
        MusicDiskCache.get(context).trust(key, full)
    }

    fun keys(track: MusicTrack): List<String> = if (track.source == MusicSource.BILIBILI) supplemental.keys(track)
        else {
            val cache = MusicDiskCache.get(context).audio
            val referenced = records(track.source).filter { it.second.optString("id") == track.id }
                .map { physical(it.first, it.second) }
            (referenced + cache.keys.filter { it.startsWith(prefix(track)) }).distinct().filter { cache.getCachedSpans(it).isNotEmpty() }
        }

    fun status(track: MusicTrack): String {
        val keys = keys(track)
        return when {
            keys.any(::complete) -> "已缓存 · 可离线播放"
            keys.isNotEmpty() -> "部分缓存"
            else -> "未缓存"
        }
    }

    /** 管理页一次扫描共享索引，不能为每一行反复解析整份离线元数据。 */
    fun snapshot(): OfflineCacheSnapshot {
        val cache = MusicDiskCache.get(context).audio
        val keys = cache.keys.filterTo(hashSetOf()) { cache.getCachedSpans(it).isNotEmpty() }
        val cached = hashSetOf<String>()
        val complete = hashSetOf<String>()
        val rows = records().map { (reference, row) -> physical(reference, row) to row } + supplemental.cacheRecords()
        rows.forEach { (key, row) ->
            if (key !in keys) return@forEach
            val source = MusicSource.entries.firstOrNull { it.name == row.optString("source") } ?: MusicSource.QQ
            val identity = cacheTrackIdentity(source, row.optString("id"))
            cached += identity
            val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            if (!row.optBoolean("pending") && !row.optBoolean("trial") && length > 0 && cache.isCached(key, 0, length)) complete += identity
        }
        val owned = MusicSource.entries.map { preferences.readSession(it).cacheNamespace() }.toSet()
        val indexedKeys = rows.mapTo(hashSetOf()) { it.first }
        keys.filter { it !in indexedKeys && it.substringBefore('|') in owned }.forEach { key ->
            val source = MusicSource.entries.firstOrNull { it.name == key.substringBefore(':') } ?: return@forEach
            val identity = cacheTrackIdentity(source, key.substringAfter('|').substringBefore('|'))
            cached += identity
            val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            if (length > 0 && cache.isCached(key, 0, length)) complete += identity
        }
        return OfflineCacheSnapshot(cached, complete)
    }

    suspend fun resolve(track: MusicTrack, preferred: AudioQuality, allowOtherQuality: Boolean): ResolvedPlayback? = withContext(Dispatchers.IO) {
        if (track.source == MusicSource.BILIBILI) return@withContext supplemental.resolve(track, preferred, allowOtherQuality)
        val candidates = keys(track).mapNotNull { key ->
            val saved = record(key)
            val url = offlinePlaybackUrl(key)
            val storedQuality = offlineRecordQuality(key, saved) ?: return@mapNotNull null
            val neteaseInfo = if (track.source == MusicSource.NETEASE)
                cachedNeteaseFlacInfo(MusicDiskCache.get(context).audio, key, url) else null
            val quality = neteaseInfo?.let { neteaseAudioQuality("", "flac", 0, it) } ?: storedQuality
            if (!DolbyPlaybackPolicy.permits(quality)) return@mapNotNull null
            if (quality == AudioQuality.DOLBY && preferred != AudioQuality.DOLBY) return@mapNotNull null
            if ((!allowOtherQuality && quality != preferred) || !verifiedComplete(key)) return@mapNotNull null
            val restored = saved?.toQqStoredTrack() ?: track
            ResolvedPlayback(restored.copy(previewUrl = url, lyrics = track.lyrics), PlaybackSource(url, preferred, quality,
                quality.bitRate, "", false, actualFormat = neteaseInfo?.description))
        }
        candidates.firstOrNull { it.source.actualQuality == preferred }
            ?: candidates.maxByOrNull { it.source.actualQuality.ordinal }
    }

    fun remove(track: MusicTrack) {
        if (track.source == MusicSource.BILIBILI) { supplemental.remove(track); return }
        keys(track).forEach { key ->
            MusicDiskCache.get(context).removeAudio(key)
            val edit = store.edit()
            records().filter { physical(it.first, it.second) == key }.forEach { edit.remove(it.first) }
            edit.apply()
        }
    }

    fun savedTracks(source: MusicSource): List<MusicTrack> {
        if (source == MusicSource.BILIBILI) return supplemental.savedTracks()
        return records(source).mapNotNull { it.second.toQqStoredTrack() }.distinctBy { it.id }
    }
}

internal data class OfflineCacheSnapshot(val cached: Set<String>, val complete: Set<String>)
