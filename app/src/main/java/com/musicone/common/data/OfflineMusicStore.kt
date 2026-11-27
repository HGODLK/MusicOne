package com.musicone

import android.content.Context
import com.musicone.sourceintegration.*
import androidx.media3.datasource.cache.ContentMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 离线元数据索引和实际音频缓存分开存，是否完整始终以磁盘上真实缓存的字节区间为准。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class OfflineMusicStore(private val context: Context) {
    private val store = context.getSharedPreferences("offline_music_index", Context.MODE_PRIVATE)
    private val supplemental = SupplementalOfflineMusicStore(context)
    private fun prefix(track: MusicTrack) = "${sourceCacheNamespace(context, track.source)}|${track.id}|"

    fun remember(resolved: ResolvedPlayback, preferred: AudioQuality = resolved.source.requestedQuality) {
        if (resolved.source.mimeType == "application/dash+xml") { supplemental.remember(resolved); return }
        if (resolved.source.trial) return
        val track = resolved.track
        val key = playbackCacheKey(context, track)
        MusicDiskCache.get(context).trust(key, !resolved.source.verificationPending)
        val reference = prefix(track) + cacheResourceVariant(key)
        store.edit().putString(reference, track.toStoredTrackJson().put("cacheKey", key)
            .put("url", resolved.source.url).putStoredQuality(resolved.source.actualQuality)
            .put("preferredQuality", preferred.name)
            .apply { if (resolved.source.actualQuality.isCustom) put("resourceFormat", resolved.source.format).put("resourceMime", resolved.source.mimeType) }
            .put("pending", resolved.source.verificationPending).toString()).apply()
    }

    fun hasNetwork(): Boolean = context.hasMusicNetwork()

    private fun records(source: MusicSource? = null, checkActive: () -> Unit = {}): List<Pair<String, JSONObject>> {
        val namespaces = (source?.let(::listOf) ?: MusicSource.entries).map { sourceCacheNamespace(context, it) }.toSet()
        return store.all.mapNotNull { (key, value) ->
            checkActive()
            if (key.substringBefore('|') !in namespaces) return@mapNotNull null
            val row = runCatching { JSONObject(value as String) }.getOrNull()
            checkActive()
            row?.let { key to it }
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

    fun availableTrackIds(): Set<String> = scanAvailableTrackIds {}

    suspend fun availableTrackIdsForDisplay(): Set<String> {
        val scanContext = currentCoroutineContext()
        return scanAvailableTrackIds { scanContext.ensureActive() }
    }

    private fun scanAvailableTrackIds(checkActive: () -> Unit): Set<String> = playbackTrace("MusicOne:offline-index-scan") {
        val verifications = linkedMapOf<String, Pair<OfflineIndexVerificationBasis, Boolean>>()
        val available = scanOfflineTrackIds(records(checkActive = checkActive), ::completeBytes, { key ->
            cachedAudioDuration(MusicDiskCache.get(context).audio, key, offlinePlaybackUrl(key))
        }, { key, aliases, full ->
            verifications[key] = OfflineIndexVerificationBasis(aliases.first().second) to full
        }, checkActive)
        if (verifications.isNotEmpty()) {
            // 把补验结果批量写回最新的记录，既不覆盖并发更新的歌曲信息，也不用每首歌重读一遍索引。
            val latest = records(checkActive = checkActive).groupBy { physical(it.first, it.second) }
            val edit = store.edit()
            val applied = linkedMapOf<String, Boolean>()
            verifications.forEach { (key, verification) ->
                checkActive()
                val aliases = latest[key] ?: return@forEach
                val (basis, full) = verification
                if (OfflineIndexVerificationBasis(aliases.first().second) != basis) return@forEach
                aliases.forEach { (reference, row) ->
                    checkActive()
                    edit.putString(reference, row.put("pending", false).put("trial", !full).toString())
                }
                applied[key] = full
            }
            checkActive()
            edit.apply()
            val cache = MusicDiskCache.get(context)
            applied.forEach { (key, full) -> cache.trust(key, full) }
        }
        checkActive()
        val supplementalIds = supplemental.completeTrackIds()
        checkActive()
        available + supplementalIds
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

    private fun ordinaryKeys(track: MusicTrack): List<String> {
        val cache = MusicDiskCache.get(context).audio
        val referenced = records(track.source).filter { it.second.optString("id") == track.id }
            .map { physical(it.first, it.second) }
        // DASH 音轨要靠 MPD 清单恢复，这里排除掉，防止被普通音频的前缀匹配扫进去。
        val dashKeys = supplemental.recordedKeys(track).toSet()
        return (referenced + cache.keys.filter { it.startsWith(prefix(track)) }).distinct()
            .filter { it !in dashKeys && cache.getCachedSpans(it).isNotEmpty() }
    }

    fun keys(track: MusicTrack): List<String> = (ordinaryKeys(track) + supplemental.keys(track)).distinct()

    fun status(track: MusicTrack): String {
        val keys = keys(track)
        return when {
            keys.any(::complete) -> "已缓存 · 可离线播放"
            keys.isNotEmpty() -> "部分缓存"
            else -> "未缓存"
        }
    }

    /** 一次性扫完离线索引生成快照给管理页用，避免每渲染一行就重解析一遍索引。 */
    fun snapshot(): OfflineCacheSnapshot {
        val cache = MusicDiskCache.get(context).audio
        val bytes = cache.keys.associateWith { key -> cache.getCachedSpans(key).sumOf { it.length } }.filterValues { it > 0 }
        val keys = bytes.keys
        val cached = hashSetOf<String>()
        val complete = hashSetOf<String>()
        val audio = mutableListOf<CachedAudioResource>()
        val retained = retainedRecords().filter { (_, row) -> row.toStoredTrack()?.let {
            it.source != MusicSource.LOCAL && SourcePackageController.get(context).running(it.source.id) == null
        } == true }
        val rows = (records().map { (reference, row) -> physical(reference, row) to row } +
            supplemental.cacheRecords() + retained.map { (reference, row) -> physical(reference, row) to row }).distinctBy { it.first }
        rows.forEach { (key, row) ->
            if (key !in keys) return@forEach
            val source = MusicSource.entries.firstOrNull { it.name == row.optString("source") } ?: MusicSource.QQ
            val identity = cacheTrackIdentity(source, row.optString("id"))
            cached += identity
            audio += CachedAudioResource(key, identity, offlineRecordQuality(key, row), bytes.getValue(key))
            val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            if (!row.optBoolean("pending") && !row.optBoolean("trial") && length > 0 && cache.isCached(key, 0, length)) complete += identity
        }
        val owned = MusicSource.entries.map { sourceCacheNamespace(context, it) }.toSet()
        val indexedKeys = rows.mapTo(hashSetOf()) { it.first }
        keys.filter { it !in indexedKeys && it.substringBefore('|') in owned }.forEach { key ->
            val source = MusicSource.entries.firstOrNull { it.name == key.substringBefore(':') } ?: return@forEach
            val identity = cacheTrackIdentity(source, key.substringAfter('|').substringBefore('|'))
            cached += identity
            audio += CachedAudioResource(key, identity, offlineRecordQuality(key, null), bytes.getValue(key))
            val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            if (length > 0 && cache.isCached(key, 0, length)) complete += identity
        }
        return OfflineCacheSnapshot(cached, complete, cachedAudioDetails(audio))
    }

    suspend fun resolve(track: MusicTrack, preferred: AudioQuality, allowOtherQuality: Boolean): ResolvedPlayback? = withContext(Dispatchers.IO) {
        val dash = supplemental.resolve(track, preferred, allowOtherQuality)
        val candidates = ordinaryKeys(track).mapNotNull { key ->
            val saved = record(key)
            val url = offlinePlaybackUrl(key)
            val storedQuality = offlineRecordQuality(key, saved) ?: return@mapNotNull null
            val mediaInfo = cachedFlacMediaInfo(MusicDiskCache.get(context).audio, key, url)
            val quality = offlineStandardQuality(storedQuality, saved?.storedQuality() != null, mediaInfo)
            if (!DolbyPlaybackPolicy.permits(quality)) return@mapNotNull null
            if (quality.capabilityQuality == AudioQuality.DOLBY && preferred.capabilityQuality != AudioQuality.DOLBY) return@mapNotNull null
            if ((!allowOtherQuality && quality != preferred) || !verifiedComplete(key)) return@mapNotNull null
            val restored = saved?.toStoredTrack() ?: track
            val resource = PlaybackSource(url, preferred, quality, quality.bitRate, if (quality.isCustom) saved?.optString("resourceFormat").orEmpty() else "",
                false, actualFormat = mediaInfo?.description, mimeType = if (quality.isCustom) saved?.optString("resourceMime")?.takeIf(String::isNotBlank) else null)
            if (quality.isCustom && !DolbyPlaybackPolicy.permits(resource)) return@mapNotNull null
            if (quality.isCustom) SupplementalPlaybackRequests.remember(resource)
            ResolvedPlayback(restored.copy(previewUrl = url, lyrics = track.lyrics), resource)
        }
        selectOfflinePlayback(preferred, candidates, dash) { preferred.fallbackCandidates(track.source) }
    }

    fun remove(track: MusicTrack) {
        val rows = records(track.source).filter { it.second.optString("id") == track.id }
        val ordinaryKeys = (ordinaryKeys(track) + rows.map { physical(it.first, it.second) }).distinct()
        supplemental.remove(track)
        ordinaryKeys.forEach { MusicDiskCache.get(context).removeAudio(it) }
        val edit = store.edit()
        rows.forEach { edit.remove(it.first) }
        edit.apply()
    }

    fun savedTracks(source: MusicSource): List<MusicTrack> {
        if (source != MusicSource.LOCAL && SourcePackageController.get(context).running(source.id) == null) {
            return retainedTrackMetadata().filter { it.source == source }.distinctBy { it.id }
        }
        return (records(source).mapNotNull { it.second.toStoredTrack() } + supplemental.savedTracks(source)).distinctBy { it.id }
    }

    // 合并读取普通和补充音源两份离线索引，只读宿主保存的公开歌曲信息，不碰音源包私有数据。
    private fun retainedRecords(): List<Pair<String, JSONObject>> = (store.all.entries.toList() +
        context.getSharedPreferences("supplemental_audio_index", Context.MODE_PRIVATE).all.entries.toList()).mapNotNull { (key, value) ->
        runCatching { key to JSONObject(value as String) }.getOrNull()
    }
    private fun retainedTrackMetadata(): List<MusicTrack> = retainedRecords().mapNotNull { it.second.toStoredTrack() }

    fun recordedSources(): Set<MusicSource> = retainedTrackMetadata().mapTo(linkedSetOf()) { it.source }
}

internal data class OfflineCacheSnapshot(
    val cached: Set<String>,
    val complete: Set<String>,
    val audioDetails: Map<String, CachedAudioDetails> = emptyMap(),
)
