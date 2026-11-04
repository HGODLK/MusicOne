package com.musicone

import android.content.Context
import com.musicone.sourceintegration.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal interface PlatformPlaybackAdapter {
    val source: MusicSource
    suspend fun enrichTrack(track: MusicTrack): MusicTrack = track
    suspend fun resolve(track: MusicTrack, quality: AudioQuality): PlaybackSource
    suspend fun recover(track: MusicTrack, quality: AudioQuality, previous: PlaybackSource): PlaybackSource = resolve(track, quality)
    suspend fun resolveVerified(track: MusicTrack, quality: AudioQuality): PlaybackSource = resolve(track, quality)
    suspend fun resolveAutomatic(track: MusicTrack, quality: AudioQuality, verifyMedia: Boolean): PlaybackSource =
        if (verifyMedia) resolveVerified(track, quality) else resolve(track, quality)
    suspend fun availableQualities(track: MusicTrack, onProgress: (List<AudioQuality>) -> Unit): List<AudioQuality>
    suspend fun lyrics(track: MusicTrack): List<TimedLyric>
}

/** 单档探测结果；超时与明确不可用必须分开，避免把超时当成降档。 */
private sealed interface TierProbe {
    data class Available(val playback: ResolvedPlayback) : TierProbe
    data object Unavailable : TierProbe
    data object TimedOut : TierProbe
}

internal class PlatformPlaybackResolver(
    private val context: Context,
    private val requestOrigin: PlaybackRequestOrigin = PlaybackRequestOrigin.PLAYBACK,
) {
    private val offline = OfflineMusicStore(context.applicationContext)
    private val cachedLyrics = CachedLyricsStore(context.applicationContext)
    private val availability = TrackPlaybackAvailability.get(context)
    init { PlaybackOptions.initialize(context) }
    private val localLibrary = MusicLibraryStore.get(context)
    private val localPlayback = LocalPlaybackAdapter(context.applicationContext)
    private val qualityCache = PlaybackQualityCache(context)
    // 后台探测最多两路并发；下载与歌词仍走各自的单路门控。
    private val probeSlots = Semaphore(2)
    private val adapters: Map<MusicSource, PlatformPlaybackAdapter>
        get() = MusicSource.entries.filter { it != MusicSource.LOCAL }
            .associateWith { SourcePlaybackAdapter(context, it, requestOrigin) }

    /** 失败恢复使用实际已解析资源，经包的恢复接口重新获得票据。 */
    suspend fun recover(track: MusicTrack, quality: AudioQuality, previous: PlaybackSource): ResolvedPlayback {
        awaitSourceStartup(track, requirePlayback = true)
        val adapter = adapters[track.source] ?: throw PlatformApiException("音源未提供恢复能力")
        return ResolvedPlayback(track, adapter.recover(track, quality, previous))
    }


    suspend fun resolve(
        track: MusicTrack,
        preferredQuality: AudioQuality,
        onlineSettleDelayMs: Long = 0L,
    ): ResolvedPlayback {
        if (track.source == MusicSource.LOCAL) {
            val local = localPlayback.enrichTrack(track)
            return ResolvedPlayback(local.copy(previewUrl = local.localUri, lyrics = localPlayback.lyrics(local)), localPlayback.resolve(local, preferredQuality))
        }
        awaitSourceStartup(track, requirePlayback = true)
        // 初始化账号后再绑定版本，迟到的取票不能污染后来登录的账号。
        sourceAccountState(context, track.source)
        val availabilityRevision = availability.revisionFor(track)
        val localLyrics = if (hasReusableLyrics(track, track.lyrics)) track.lyrics
            else cachedLyrics.read(track).ifEmpty {
                cachedLyrics.readFallback(track)
            }
        val readyTrack = track.copy(lyrics = localLyrics)
        val preferExactOnline = preferredQuality.capabilityQuality == AudioQuality.DOLBY &&
            DolbyPlaybackPolicy.permits(preferredQuality) && offline.hasNetwork()
        return try { resolvePlaybackCacheFirst(preferExactOnline,
            cached = { allowOther -> offline.resolve(readyTrack, preferredQuality, allowOtherQuality = allowOther) },
            online = {
                // 在线取票等待切歌动作稳定，取消期间不产生已经来不及撤回的 HTTP 请求。
                if (onlineSettleDelayMs > 0L) delay(onlineSettleDelayMs)
                resolveOnline(readyTrack, preferredQuality, verifyMedia = false)
            },
        ).also { availability.report(track, availabilityRevision, null) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val reason = when {
                error is TrackUnavailableException -> error.message
                else -> null
            }
            availability.report(track, availabilityRevision, reason)
            throw if (reason != null) TrackUnavailableException(reason) else error
        }
    }

    /** 预加载入口：后台按首选档探测，必要时并发无损通道，失败再逐档回退。 */
    suspend fun resolvePreferred(track: MusicTrack, preferredQuality: AudioQuality): ResolvedPlayback {
        awaitSourceStartup(track, requirePlayback = true)
        val requested = automaticQualityForSource(track.source, preferredQuality)
        if (track.source == MusicSource.LOCAL) return resolve(track, requested)
        offline.resolve(track, requested, allowOtherQuality = false)?.let { return it }
        val probe = probeBest(track, requested)
        val resolved = probe.playback ?: throw PlatformApiException("歌曲暂无可用播放地址")
        if (!resolved.source.trial) offline.remember(resolved, requested)
        return resolved
    }

    /** 无缓存时的首次探测：Hi-Res/杜比走两路并发，其余从上往下逐档。 */
    suspend fun probeBest(track: MusicTrack, preferredQuality: AudioQuality): PlaybackQualityProbe {
        awaitSourceStartup(track, requirePlayback = true)
        val requested = automaticQualityForSource(track.source, preferredQuality)
        if (track.source == MusicSource.LOCAL) return PlaybackQualityProbe(resolve(track, requested), true)
        if (!offline.hasNetwork()) {
            return PlaybackQualityProbe(offline.resolve(track, requested, allowOtherQuality = false), false)
        }
        val ready = prepareTrack(track)
        val lanes = playbackParallelLanes(requested).filter { it in playbackQualityLadder(track.source, requested) && DolbyPlaybackPolicy.permits(it) }
        val timedOut = mutableSetOf<AudioQuality>()
        if (lanes.isNotEmpty()) {
            val outcomes = supervisorScope {
                lanes.map { quality ->
                    async { quality to probeTierTimed(ready, quality, PLAYBACK_QUALITY_GROUP_WINDOW_MS) }
                }.awaitAll()
            }
            val successes = mutableListOf<Pair<AudioQuality, ResolvedPlayback>>()
            for ((quality, outcome) in outcomes) {
                when (outcome) {
                    is TierProbe.Available -> successes += quality to outcome.playback
                    TierProbe.TimedOut -> timedOut += quality
                    TierProbe.Unavailable -> {}
                }
            }
            bestProbe(track, successes, timedOut)?.let { return it }
        }
        for (quality in playbackQualityLadder(track.source, requested)) {
            if (quality in lanes) continue
            when (val outcome = probeTierTimed(ready, quality, PLAYBACK_QUALITY_GROUP_WINDOW_MS)) {
                is TierProbe.Available -> return bestProbe(
                    track,
                    listOf(quality to outcome.playback),
                    timedOut,
                ) ?: PlaybackQualityProbe(outcome.playback, false)
                TierProbe.TimedOut -> timedOut += quality
                TierProbe.Unavailable -> {}
            }
        }
        return PlaybackQualityProbe(null, false)
    }

    /**
     * 已有缓存时从首选档往下对比，一直走到缓存档。
     * 更高档超时只会让本次暂用低档，不会把低档写死进缓存。
     */
    suspend fun probeComparison(
        track: MusicTrack,
        preferredQuality: AudioQuality,
        cached: AudioQuality,
    ): PlaybackQualityProbe {
        awaitSourceStartup(track, requirePlayback = true)
        val requested = automaticQualityForSource(track.source, preferredQuality)
        val ready = prepareTrack(track)
        val timedOut = mutableSetOf<AudioQuality>()
        for (quality in playbackComparisonLadder(track.source, requested, cached)) {
            when (val outcome = probeTierTimed(ready, quality, PLAYBACK_QUALITY_COMPARISON_TIMEOUT_MS)) {
                is TierProbe.Available -> {
                    val cacheable = playbackProbeCacheable(quality, timedOut)
                    if (cacheable) qualityCache.write(track, outcome.playback.source.actualQuality)
                    else qualityCache.upgrade(track, outcome.playback.source.actualQuality)
                    return PlaybackQualityProbe(outcome.playback, cacheable)
                }
                TierProbe.TimedOut -> timedOut += quality
                TierProbe.Unavailable -> {}
            }
        }
        return PlaybackQualityProbe(null, false)
    }

    /** 从缓存档开始尝试；失败或超时后才继续往下。 */
    suspend fun probeAt(
        track: MusicTrack,
        preferredQuality: AudioQuality,
        cached: AudioQuality,
    ): PlaybackQualityProbe {
        awaitSourceStartup(track, requirePlayback = true)
        val requested = automaticQualityForSource(track.source, preferredQuality)
        val start = playbackCachedStart(cached, track.source, requested) ?: return probeBest(track, requested)
        val ready = prepareTrack(track)
        val ladder = playbackQualityLadder(track.source, requested)
        val index = if (requested.isCustom) ladder.indexOf(start) else ladder.indexOfFirst { it.ordinal <= start.ordinal }
        val tiers = if (index >= 0) ladder.drop(index) else ladder
        val timedOut = mutableSetOf<AudioQuality>()
        for (quality in tiers) {
            when (val outcome = probeTierTimed(ready, quality, PLAYBACK_QUALITY_GROUP_WINDOW_MS)) {
                is TierProbe.Available -> {
                    val cacheable = playbackProbeCacheable(quality, timedOut)
                    if (cacheable) qualityCache.write(track, outcome.playback.source.actualQuality)
                    else qualityCache.upgrade(track, outcome.playback.source.actualQuality)
                    return PlaybackQualityProbe(outcome.playback, cacheable)
                }
                TierProbe.TimedOut -> timedOut += quality
                TierProbe.Unavailable -> {}
            }
        }
        return PlaybackQualityProbe(null, false)
    }

    private fun bestProbe(
        track: MusicTrack,
        successes: List<Pair<AudioQuality, ResolvedPlayback>>,
        timedOut: Set<AudioQuality>,
    ): PlaybackQualityProbe? {
        val best = successes.maxByOrNull { it.first.ordinal } ?: return null
        val cacheable = playbackProbeCacheable(best.first, timedOut)
        if (cacheable) qualityCache.write(track, best.second.source.actualQuality)
        else qualityCache.upgrade(track, best.second.source.actualQuality)
        return PlaybackQualityProbe(best.second, cacheable)
    }

    /** 下载前把已取到的完整音源登记进离线索引，保留现有缓存管理语义。 */
    fun rememberPreloaded(resolved: ResolvedPlayback, preferred: AudioQuality) =
        offline.remember(resolved, preferred)

    private suspend fun probeTierTimed(track: MusicTrack, quality: AudioQuality, timeoutMs: Long): TierProbe =
        withTimeoutOrNull(timeoutMs) { probeTier(track, quality, verify = true) } ?: TierProbe.TimedOut

    private suspend fun probeTier(track: MusicTrack, quality: AudioQuality, verify: Boolean): TierProbe {
        if (!DolbyPlaybackPolicy.permits(quality)) return TierProbe.Unavailable
        offline.resolve(track, quality, allowOtherQuality = false)?.let { return TierProbe.Available(it) }
        if (!offline.hasNetwork()) return TierProbe.Unavailable
        if (!track.playable) throw TrackUnavailableException(track.unavailableReason ?: "此资源已失效")
        val adapter = adapters[track.source] ?: throw PlatformApiException("${track.source.label}尚未接入播放")
        return probeSlots.withPermit {
            try {
                val source = adapter.resolveAutomatic(track, quality, verify)
                val lower = quality.isHigherThan(source.actualQuality) || (quality.isCustom && source.actualQuality != quality)
                val lowerAccepted = source.actualQuality.playbackEquivalent() == quality.playbackEquivalent()
                if ((source.actualQuality.isCustom && !DolbyPlaybackPolicy.permits(source)) || source.trial || (lower && !lowerAccepted)) {
                    TierProbe.Unavailable
                } else {
                    registerResolvedSource(source)
                    TierProbe.Available(ResolvedPlayback(track.copy(previewUrl = source.url), source))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (error.stopsSourcePlaybackFallback()) throw error
                TierProbe.Unavailable
            }
        }
    }

    private suspend fun prepareTrack(track: MusicTrack): MusicTrack =
        adapters[track.source]?.enrichTrack(track)
            ?: throw PlatformApiException("${track.source.label}尚未接入播放")

    private fun registerResolvedSource(source: PlaybackSource) {
        // 包提供的稳定资源键与具体请求地址保持绑定。
        source.cacheKey?.let { SupplementalPlaybackRequests.register(source.url, source.requestHeaders, it) }
    }

    private suspend fun resolveOnline(track: MusicTrack, preferredQuality: AudioQuality, verifyMedia: Boolean): ResolvedPlayback {
        if (!offline.hasNetwork()) throw PlatformApiException("这首歌曲尚未完整缓存，请联网后缓存")
        if (!track.playable) throw TrackUnavailableException(track.unavailableReason ?: "此资源已失效")
        val adapter = adapters[track.source] ?: throw PlatformApiException("${track.source.label}尚未接入播放")
        val resolvedTrack = adapter.enrichTrack(track)
        var trialFallback: PlaybackSource? = null
        var unavailable: TrackUnavailableException? = null
        var uncertain: Throwable? = null
        val cachedStart = playbackCachedStart(qualityCache.read(track), track.source, preferredQuality)
        val ladder = playbackQualityLadder(track.source, preferredQuality)
        val candidates = (if (cachedStart != null) {
            if (preferredQuality.isCustom) ladder.drop(ladder.indexOf(cachedStart).coerceAtLeast(0))
            else listOf(cachedStart) + ladder.filter { it.ordinal < cachedStart.ordinal }
        } else {
            ladder
        }).distinct().filter(DolbyPlaybackPolicy::permits)
        val source = candidates.firstNotNullOfOrNull { quality ->
            try {
                val candidate = adapter.resolveAutomatic(resolvedTrack, quality, verifyMedia)
                if ((candidate.actualQuality.isCustom && !DolbyPlaybackPolicy.permits(candidate)) || (candidate.actualQuality.isCustom &&
                    sourceCatalogQualities(track.source)?.none { it == candidate.actualQuality } != false)) {
                    null
                } else if (candidate.trial) {
                    // 高档试听源不能冒充完整音质；继续寻找较低档的完整地址。
                    if (trialFallback == null) trialFallback = candidate
                    null
                } else {
                    candidate
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (error.stopsSourcePlaybackFallback()) throw error
                if (error is TrackUnavailableException) unavailable = error else uncertain = error
                null
            }
        } ?: trialFallback ?: throw (uncertain ?: unavailable ?: PlatformApiException("歌曲暂无可用播放地址"))
        source.cacheKey?.let { SupplementalPlaybackRequests.register(source.url, source.requestHeaders, it) }
        if (source.cacheKey == null) {
            val key = if (source.trial) "uncached-trial:${resolvedTrack.cacheIdentity()}"
                else MusicDiskCache.get(context).register(networkPlaybackCacheKey(context, resolvedTrack.copy(previewUrl = source.url)), resolvedTrack)
            SupplementalPlaybackRequests.register(source.url, source.requestHeaders, key)
        }
        if (!source.trial) qualityCache.write(track, source.actualQuality)
        return ResolvedPlayback(resolvedTrack.copy(previewUrl = source.url), source)
            .also { offline.remember(it, preferredQuality) }
    }

    suspend fun enrichMetadata(track: MusicTrack): MusicTrack {
        awaitSourceStartup(track)
        return if (offline.hasNetwork()) adapters[track.source]?.enrichTrack(track) ?: track else track
    }

    suspend fun resolveExact(track: MusicTrack, quality: AudioQuality): PlaybackSource {
        if (track.source == MusicSource.LOCAL) throw PlatformApiException("本地文件按原始规格播放")
        awaitSourceStartup(track, requirePlayback = true)
        DolbyPlaybackPolicy.requireAllowed(quality)
        offline.resolve(track, quality, allowOtherQuality = false)?.let { return it.source }
        if (!offline.hasNetwork()) throw PlatformApiException("此音质尚未缓存")
        val adapter = adapters[track.source] ?: throw PlatformApiException("${track.source.label}尚未接入播放")
        val source = adapter.resolveVerified(track, quality)
        if (source.actualQuality.isCustom) DolbyPlaybackPolicy.requireAllowed(source)
        if (source.trial) {
            throw PlatformApiException("当前歌曲只返回${quality.displayLabel(track.source)}试听音源")
        }
        if (source.actualQuality.playbackEquivalent() != quality.playbackEquivalent()) {
            throw PlatformApiException("平台未返回${quality.displayLabel(track.source)}完整音源")
        }
        offline.remember(ResolvedPlayback(track.copy(previewUrl = source.url), source))
        return source
    }

    suspend fun availableQualities(
        track: MusicTrack,
        onProgress: (List<AudioQuality>) -> Unit = {},
    ): List<AudioQuality> {
        if (track.source == MusicSource.LOCAL) return emptyList()
        awaitSourceStartup(track, requirePlayback = true)
        if (!offline.hasNetwork()) return (sourceCatalogQualities(track.source) ?: AudioQuality.entries).filter {
            offline.resolve(track, it, allowOtherQuality = false) != null
        }
        val adapter = adapters[track.source] ?: return emptyList()
        val values = adapter.availableQualities(adapter.enrichTrack(track), onProgress)
        val preferred = PlaybackQualityPreferences(context).read(track.source)
        val best = if (preferred.isCustom) playbackQualityLadder(track.source, preferred).firstOrNull { it in values }
            else values.maxOrNull()
        best?.let { qualityCache.write(track, it) }
        return values
    }

    private val lyricRequest = PlaybackLyricRequest(
        session = { track -> PlaybackLyricSession(sourceCacheNamespace(context, track.source),
            sourceAccountState(context, track.source)?.revision?.toString().orEmpty()) },
        readCached = cachedLyrics::read, readFallback = cachedLyrics::readFallback,
        fetch = { track -> adapters[track.source]?.lyrics(track).orEmpty() },
        save = { track, value, namespace -> cachedLyrics.save(track, value, namespace) },
        online = offline::hasNetwork,
    )
    suspend fun lyrics(track: MusicTrack): List<TimedLyric> {
        awaitSourceStartup(track)
        return preferredLyrics(
            track.source.supportsManualLyrics, track.source.supportsOnlineLyrics,
            readLibrary = { withContext(Dispatchers.IO) { localLibrary.lyrics(track) } },
            readOnline = { lyricRequest.load(track) },
        ).ifEmpty { if (!track.source.supportsOnlineLyrics) cachedLyrics.read(track) else emptyList() }
    }

    /** 快速切歌预览只读本地资料和缓存，不因允许手动歌词而提前发起在线请求。 */
    suspend fun cachedOrLibraryLyrics(track: MusicTrack): List<TimedLyric> {
        awaitSourceStartup(track)
        return preferredLyrics(
            track.source.supportsManualLyrics, track.source.supportsOnlineLyrics,
            readLibrary = { withContext(Dispatchers.IO) { localLibrary.lyrics(track) } },
            readOnline = { cachedLyrics.read(track) },
        ).ifEmpty { if (!track.source.supportsOnlineLyrics) cachedLyrics.read(track) else emptyList() }
    }

    private suspend fun awaitSourceStartup(track: MusicTrack, requirePlayback: Boolean = false) {
        if (track.source == MusicSource.LOCAL) return
        SourcePackageController.get(context).awaitStartup()
        if (requirePlayback) {
            val status = queueSourceStatus(track.source)
            if (status != QueueSourceStatus.AVAILABLE) throw TrackUnavailableException(status.message(track.source))
        }
    }
}
