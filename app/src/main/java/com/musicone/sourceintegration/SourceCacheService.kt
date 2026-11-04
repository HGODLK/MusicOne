package com.musicone.sourceintegration

import android.content.Context
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheWriter
import com.musicone.*
import com.musicone.source.sdk.*
import com.musicone.source.runtime.SourceCommitGuard
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/** 包传回真实资源，宿主统一执行容量准入、写入、完整性检查与取消。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SourceCacheService(private val context: Context) : SourceCacheHost {
    private data class Task(val job: Job, @Volatile var writer: CacheWriter? = null)
    private val tasks = ConcurrentHashMap<String, Task>()
    private fun identity(track: SourceTrackDetails) = "${track.track.identity.sourceId}|${track.track.identity.accountId.orEmpty()}|${track.stableId}"
    override suspend fun lookup(track: SourceTrackDetails, qualityId: String?): SourceCacheEntry? {
        val song = track.toMusicTrack()
        val quality = sourceAudioQuality(qualityId.orEmpty(), song.source) ?: PlaybackQualityPreferences(context).read(song.source)
        val resolved = OfflineMusicStore(context).resolve(song, quality, qualityId == null) ?: return null
        return SourceCacheEntry(track.stableId, true, resolved.source.url, resolved.source.mimeType)
    }
    override suspend fun download(track: SourceTrackDetails, resource: SourcePlaybackResource) = withContext(Dispatchers.IO) {
        require(!resource.trial) { "试听音源不能作为完整缓存" }
        val song = track.toMusicTrack()
        fun accountSignature() = sourceAccountState(context, song.source).let { Triple(it?.status, it?.account?.id, it?.revision) }
        val requestedAccount = accountSignature()
        val commitGuard = currentCoroutineContext()[SourceCommitGuard]
        val disk = MusicDiskCache.get(context)
        disk.blockReason(song)?.let { throw CacheQuotaException(it) }
        val catalog = sourceCatalogQualities(song.source)
        if (catalog != null && catalog.none { it.qualityId == resource.qualityId })
            throw PlatformApiException("音源返回的缓存音质未列入目录")
        val quality = sourceAudioQuality(resource.qualityId, song.source) ?: AudioQuality.STANDARD
        val source = resource.toPlaybackSource(quality, disk.register(resource.address.cacheIdentity, song), song.source)
        if (quality.isCustom) DolbyPlaybackPolicy.requireAllowed(source)
        val resolved = ResolvedPlayback(song.copy(previewUrl = source.url), source)
        val key = playbackCacheKey(context, resolved.track)
        val task = Task(currentCoroutineContext().job)
        val id = identity(track)
        tasks.put(id, task)?.let { it.writer?.cancel(); it.job.cancel() }
        val offline = OfflineMusicStore(context)
        try {
            ensureActive()
            offline.remember(resolved, quality)
            val upstream = SupplementalPlaybackRequests.factory(DefaultDataSource.Factory(context,
                DefaultHttpDataSource.Factory().setConnectTimeoutMs(8_000).setReadTimeoutMs(8_000)))
            val url = cacheDownloadUrl(source)
            val downloadSpec = cacheDownloadSpec(source, upstream, disk.audio, key) { write ->
                ensureActive()
                check(accountSignature() == requestedAccount) { "账号已变化，缓存任务已停止" }
                if (commitGuard != null) commitGuard.commit(write) else write()
            }
            val writer = CacheWriter(disk.streamingFactory(upstream, strict = true).createDataSource(),
                downloadSpec, null) { _, _, _ -> ensureActive() }
            task.writer = writer
            ensureActive()
            writer.cache()
            ensureActive()
            if (source.verificationPending) {
                val duration = cachedAudioDuration(disk.audio, key, url)
                val full = duration != null && mediaDurationMatches(duration, song.durationMs)
                offline.verify(key, full)
                check(full) { "该音源不是完整歌曲" }
            }
            check(offline.complete(key)) { "音频未完整缓存，请重试" }
            disk.audioChanged()
        } finally {
            task.writer = null
            tasks.remove(id, task)
        }
    }
    override suspend fun cancel(track: SourceTrackDetails) {
        tasks.remove(identity(track))?.let { it.writer?.cancel(); it.job.cancel() }
    }
}
