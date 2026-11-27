package com.musicone

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.datasource.cache.ContentMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 跟踪当前在线播放地址的有效期（用包含息屏休眠的单调时钟），
 * 本地文件和已整首缓存的歌不算过期。
 */
internal class PlaybackTicketTracker(
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val ttlMs: (MusicSource) -> Long = ::playbackTicketTtlMs,
    private val refreshLeadMs: Long = PLAYBACK_TICKET_REFRESH_MARGIN_MS,
) {
    private data class ActiveTicket(
        val generation: Long,
        val trackId: String,
        val source: MusicSource,
        val url: String,
        val createdAtMs: Long,
        val expiresAtMs: Long,
    )

    @Volatile private var active: ActiveTicket? = null

    fun record(generation: Long, track: MusicTrack, source: PlaybackSource?) {
        val url = source?.url?.takeIf(String::isNotBlank) ?: track.previewUrl
        if (track.source == MusicSource.LOCAL || url.isBlank() || offlinePlaybackKey(url) != null) {
            active = null
            return
        }
        val now = nowMs()
        val validForMs = source?.expiresAtEpochMs?.let { (it - wallClockMs()).coerceAtLeast(0L) }
            ?: ttlMs(track.source).coerceAtLeast(0L)
        val expiresAt = if (now > Long.MAX_VALUE - validForMs) Long.MAX_VALUE else now + validForMs
        active = ActiveTicket(generation, track.id, track.source, url, now, expiresAt)
    }

    fun clear() {
        active = null
    }

    fun needsRenewal(generation: Long, track: MusicTrack): Boolean {
        val url = track.previewUrl
        if (track.source == MusicSource.LOCAL || url.isBlank() || offlinePlaybackKey(url) != null) return false
        val current = active ?: return false
        if (current.generation != generation || current.trackId != track.id ||
            current.source != track.source || current.url != url) return false
        val now = nowMs()
        if (now < current.createdAtMs) return true
        val lead = refreshLeadMs.coerceAtLeast(0L)
        val refreshAt = if (current.expiresAtMs < lead) 0L else current.expiresAtMs - lead
        return now >= refreshAt
    }
}

/**
 * 应用回到前台时，如果当前歌处于暂停状态且播放地址已过期，就在后台静默刷新地址。
 * 优先走恢复接口，失败再走完整解析；刷新失败也不报错，等用户真正点播放时再重新拉取。
 */
internal class PlaybackTicketRefresher(
    private val scope: CoroutineScope,
    private val recover: suspend (MusicTrack, AudioQuality, PlaybackSource) -> ResolvedPlayback,
    private val resolve: suspend (MusicTrack, AudioQuality, Long) -> ResolvedPlayback,
    private val isCurrent: (PlaybackRequestKey) -> Boolean,
    private val onRenewed: (PlaybackRequestKey, ResolvedPlayback) -> Unit,
) {
    private var job: Job? = null
    private var activeKey: PlaybackRequestKey? = null

    val isRenewing: Boolean get() = job?.isActive == true

    fun cancel() {
        job?.cancel()
        job = null
        activeKey = null
    }

    fun renewIfIdle(
        track: MusicTrack,
        key: PlaybackRequestKey,
        recoverQuality: AudioQuality,
        fallbackQuality: AudioQuality,
        previous: PlaybackSource?,
    ) {
        if (job?.isActive == true && activeKey == key) return
        cancel()
        activeKey = key
        val launched = scope.launch {
            try {
                val renewed = resolveFresh(track, recoverQuality, fallbackQuality, previous)
                if (!isCurrent(key)) return@launch
                // 原来是完整版时，静默刷新如果只拿到试听版就直接丢弃。
                if (previous?.trial == false && renewed.source.trial) return@launch
                onRenewed(key, renewed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("MusicPlayback", "回前台静默刷新票据失败：${error.javaClass.simpleName}")
            } finally {
                if (activeKey == key) {
                    activeKey = null
                    job = null
                }
            }
        }
        job = launched
    }

    private suspend fun resolveFresh(
        track: MusicTrack,
        recoverQuality: AudioQuality,
        fallbackQuality: AudioQuality,
        previous: PlaybackSource?,
    ): ResolvedPlayback {
        val recoverable = previous?.takeIf { it.sourceResource != null }
        if (recoverable != null) {
            try {
                return recover(track, recoverQuality, recoverable)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 恢复接口失败时，回退走完整解析流程。
            }
        }
        return resolve(track, fallbackQuality, 0L)
    }
}

/** 只查内存里的磁盘缓存索引（不触发磁盘 IO），如果整首歌已缓存就不用再去刷新在线地址。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun isTrackAudioFullyCached(context: Context, track: MusicTrack): Boolean {
    if (track.source == MusicSource.LOCAL) return true
    if (offlinePlaybackKey(track.previewUrl) != null) return true
    val cache = MusicDiskCache.available()?.audio ?: return false
    val key = SupplementalPlaybackRequests.cacheKey(track.previewUrl) ?: playbackCacheKey(context, track)
    val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
    return length > 0 && cache.isCached(key, 0, length)
}
