package com.musicone.demo

import android.content.Context
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
    qqCacheRequests: Boolean = false,
    qqRequestOrigin: QqRequestOrigin = if (qqCacheRequests) QqRequestOrigin.BATCH_CACHE else QqRequestOrigin.PLAYBACK,
) {
    private val offline = OfflineMusicStore(context.applicationContext)
    private val cachedLyrics = CachedLyricsStore(context.applicationContext)
    private val preferences = PlatformPreferences(context)
    private val availability = TrackPlaybackAvailability.get(context)
    private val availabilityVerifier = TrackAvailabilityVerifier(context)
    init { PlaybackOptions.initialize(context) }
    private val qqArtworkAliases = QqArtworkAliasStore(context)
    private val localLibrary = MusicLibraryStore.get(context)
    private val localPlayback = LocalPlaybackAdapter(context.applicationContext)
    private val qualityCache = PlaybackQualityCache(context)
    // 后台探测最多两路并发；下载与歌词仍走各自的单路门控。
    private val probeSlots = Semaphore(2)
    private val adapters: Map<MusicSource, PlatformPlaybackAdapter> = listOf(
        NeteasePlaybackAdapter(NeteaseApiClient { preferences.readSession(MusicSource.NETEASE).deviceId }) { preferences.credential(MusicSource.NETEASE) },
        QqPlaybackAdapter(
            QqApiClient(),
            { preferences.credential(MusicSource.QQ) },
            { preferences.readSession(MusicSource.QQ).account?.hasVipAccess == true },
            { qqStableGuid(preferences.readSession(MusicSource.QQ).deviceId) },
            qqArtworkAliases,
            qqRequestOrigin,
        ),
        KugouPlaybackAdapter(KugouApiClient()) { preferences.credential(MusicSource.KUGOU) },
        BilibiliPlaybackAdapter(context.applicationContext),
    ).associateBy(PlatformPlaybackAdapter::source)

    suspend fun resolve(
        track: MusicTrack,
        preferredQuality: AudioQuality,
        onlineSettleDelayMs: Long = 0L,
    ): ResolvedPlayback {
        if (track.source == MusicSource.LOCAL) {
            val local = localPlayback.enrichTrack(track)
            return ResolvedPlayback(local.copy(previewUrl = local.localUri, lyrics = localPlayback.lyrics(local)), localPlayback.resolve(local, preferredQuality))
        }
        // 初始化账号后再绑定版本，迟到的取票不能污染后来登录的账号。
        preferences.readSession(track.source)
        val availabilityRevision = availability.state.value.revision
        val localLyrics = if (hasReusableLyrics(track, track.lyrics)) track.lyrics
            else cachedLyrics.read(track).ifEmpty {
                if (track.source == MusicSource.NETEASE || !offline.hasNetwork()) cachedLyrics.readFallback(track) else emptyList()
            }
        val readyTrack = (if (track.source == MusicSource.QQ) QqEntitlements.displayTrack(track, QqEntitlements.state.value)
            else track).copy(lyrics = localLyrics)
        val preferExactOnline = track.source in setOf(MusicSource.QQ, MusicSource.BILIBILI) && preferredQuality == AudioQuality.DOLBY &&
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
                error is PlatformApiException && !error.stopsPlaybackFallback() &&
                    track.source in setOf(MusicSource.QQ, MusicSource.NETEASE) -> availabilityVerifier.verify(track)
                else -> null
            }
            availability.report(track, availabilityRevision, reason)
            throw if (reason != null) TrackUnavailableException(reason) else error
        }
    }

    /** 预加载入口：后台按首选档探测，必要时并发无损通道，失败再逐档回退。 */
    suspend fun resolvePreferred(track: MusicTrack, preferredQuality: AudioQuality): ResolvedPlayback {
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
        val requested = automaticQualityForSource(track.source, preferredQuality)
        if (track.source == MusicSource.LOCAL) return PlaybackQualityProbe(resolve(track, requested), true)
        if (!offline.hasNetwork()) {
            return PlaybackQualityProbe(offline.resolve(track, requested, allowOtherQuality = false), false)
        }
        val ready = prepareTrack(track)
        val lanes = playbackParallelLanes(requested).filter(DolbyPlaybackPolicy::permits)
        val timedOut = mutableSetOf<AudioQuality>()
        if (lanes.isNotEmpty() && track.source != MusicSource.BILIBILI) {
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
        val requested = automaticQualityForSource(track.source, preferredQuality)
        val start = playbackCachedStart(cached, track.source, requested) ?: return probeBest(track, requested)
        val ready = prepareTrack(track)
        val ladder = playbackQualityLadder(track.source, requested)
        val index = ladder.indexOfFirst { it.ordinal <= start.ordinal }
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
        if (track.source == MusicSource.BILIBILI && !track.playable) {
            throw TrackUnavailableException(track.unavailableReason ?: "B站资源已失效")
        }
        val adapter = adapters[track.source] ?: throw PlatformApiException("${track.source.label}尚未接入播放")
        return probeSlots.withPermit {
            try {
                val source = if (verify) adapter.resolveVerified(track, quality) else adapter.resolve(track, quality)
                val lower = source.actualQuality.ordinal < quality.ordinal
                val lowerAccepted = track.source == MusicSource.BILIBILI ||
                    (track.source == MusicSource.NETEASE &&
                        isNeteaseQualitySatisfied(quality, source.actualQuality))
                if (source.trial || (lower && !lowerAccepted)) {
                    TierProbe.Unavailable
                } else {
                    registerResolvedSource(source)
                    TierProbe.Available(ResolvedPlayback(track.copy(previewUrl = source.url), source))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (error.stopsPlaybackFallback()) throw error
                TierProbe.Unavailable
            }
        }
    }

    private suspend fun prepareTrack(track: MusicTrack): MusicTrack {
        val aliased = qqArtworkAliases.apply(track)
        val adapter = adapters[track.source] ?: throw PlatformApiException("${track.source.label}尚未接入播放")
        return if (aliased.requiresQqPlaybackMetadata() ||
            (aliased.source == MusicSource.BILIBILI && (aliased.bilibiliCid <= 0L || aliased.durationMs <= 0L))) {
            adapter.enrichTrack(aliased)
        } else {
            aliased
        }
    }

    private fun registerResolvedSource(source: PlaybackSource) {
        // 只登记平台自带缓存键（B站 DASH 等）；普通音源在真正下载或播放时再建索引。
        source.cacheKey?.let { SupplementalPlaybackRequests.register(source.url, source.requestHeaders, it) }
    }

    private suspend fun resolveOnline(track: MusicTrack, preferredQuality: AudioQuality, verifyMedia: Boolean): ResolvedPlayback {
        if (!offline.hasNetwork()) throw PlatformApiException("这首歌曲尚未完整缓存，请联网后缓存")
        if (track.source == MusicSource.BILIBILI && !track.playable) {
            throw TrackUnavailableException(track.unavailableReason ?: "B站资源已失效")
        }
        val adapter = adapters[track.source] ?: throw PlatformApiException("${track.source.label}尚未接入播放")
        val aliasedTrack = qqArtworkAliases.apply(track)
        // 音乐杂志详情卡可能只有数字 songId 和零时长。取票本来就要补齐 MID，
        // 这里把同一次补全结果继续交给播放器，供待验证 FLAC 按真实时长完成确认。
        val resolvedTrack = if (aliasedTrack.requiresQqPlaybackMetadata() ||
            (aliasedTrack.source == MusicSource.BILIBILI && (aliasedTrack.bilibiliCid <= 0L || aliasedTrack.durationMs <= 0L))) {
            adapter.enrichTrack(aliasedTrack)
        } else {
            aliasedTrack
        }
        var trialFallback: PlaybackSource? = null
        var unavailable: TrackUnavailableException? = null
        var uncertain: Throwable? = null
        val cachedStart = playbackCachedStart(qualityCache.read(track), track.source, preferredQuality)
        val ladder = playbackQualityLadder(track.source, preferredQuality)
        val candidates = (if (cachedStart != null) {
            if (track.source == MusicSource.BILIBILI && cachedStart.ordinal < preferredQuality.ordinal) {
                // B站旧缓存可能被记成普通；先按当前首选完整走一遍，避免低档缓存压住 HiRes。
                ladder
            } else {
                // 已知缓存档可用时不再回头尝试更高档；更高档的升级交给后台对比探测。
                listOf(cachedStart) + ladder.filter { it.ordinal < cachedStart.ordinal }
            }
        } else {
            ladder
        }).distinct().filter(DolbyPlaybackPolicy::permits)
            .let { values -> if (track.source == MusicSource.KUGOU) values.filterNot { it == AudioQuality.HIGHER } else values }
        val source = candidates.firstNotNullOfOrNull { quality ->
            try {
                val candidate = adapter.resolveAutomatic(resolvedTrack, quality, verifyMedia)
                if (candidate.trial) {
                    // 高档试听源不能冒充完整音质；继续寻找较低档的完整地址。
                    if (trialFallback == null) trialFallback = candidate
                    null
                } else {
                    candidate
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (error.stopsPlaybackFallback()) throw error
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

    suspend fun enrichMetadata(track: MusicTrack): MusicTrack =
        if (offline.hasNetwork()) adapters[track.source]?.enrichTrack(track) ?: track else track

    suspend fun resolveExact(track: MusicTrack, quality: AudioQuality): PlaybackSource {
        if (track.source == MusicSource.LOCAL) throw PlatformApiException("本地文件按原始规格播放")
        DolbyPlaybackPolicy.requireAllowed(quality)
        offline.resolve(track, quality, allowOtherQuality = false)?.let { return it.source }
        if (!offline.hasNetwork()) throw PlatformApiException("此音质尚未缓存")
        val adapter = adapters[track.source] ?: throw PlatformApiException("${track.source.label}尚未接入播放")
        val source = adapter.resolveVerified(track, quality)
        if (source.trial) {
            throw PlatformApiException("当前歌曲只返回${quality.displayLabel(track.source)}试听音源")
        }
        if (source.actualQuality != quality && (track.source != MusicSource.NETEASE || !isNeteaseQualitySatisfied(quality, source.actualQuality))) {
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
        if (!offline.hasNetwork()) return AudioQuality.entries.filter {
            offline.resolve(track, it, allowOtherQuality = false) != null
        }
        val adapter = adapters[track.source] ?: return emptyList()
        val values = adapter.availableQualities(adapter.enrichTrack(track), onProgress)
        values.maxOrNull()?.let { qualityCache.write(track, it) }
        return values
    }

    suspend fun lyrics(track: MusicTrack): List<TimedLyric> {
        if (track.source.supportsLibraryEditing) return withContext(Dispatchers.IO) { localLibrary.lyrics(track) }
        if (hasReusableLyrics(track, track.lyrics) && !shouldUpgradeLyricCacheToVerbatim(track, track.lyrics)) {
            cachedLyrics.save(track, track.lyrics)
            return track.lyrics
        }
        val namespace = preferences.readSession(track.source).cacheNamespace()
        val cached = cachedLyrics.read(track, namespace)
        val needsUpgrade = shouldUpgradeLyricCacheToVerbatim(track, cached)
        if (cached.isNotEmpty() && !needsUpgrade) return cached
        val fallback = cached.ifEmpty { cachedLyrics.readFallback(track, namespace) }
            .ifEmpty { track.lyrics.takeIf(::validLyricTimeline).orEmpty() }
        if (!offline.hasNetwork()) {
            if (fallback.isEmpty()) throw java.io.IOException("网络不可用，歌词可稍后重试")
            return fallback
        }
        val fetched = fetchLyricsPreservingFallback(track, fallback) { adapters[track.source]?.lyrics(track).orEmpty() }
        if (hasReusableLyrics(track, fetched) ||
            (track.source in setOf(MusicSource.NETEASE, MusicSource.KUGOU) && fetched.isNotEmpty() && validLyricTimeline(fetched))) {
            if (preferences.readSession(track.source).cacheNamespace() == namespace) cachedLyrics.save(track, fetched, namespace)
            lyricDiagnostic(track, "网络歌词就绪", fetched)
            return fetched
        }
        lyricDiagnostic(track, "网络无结果，保留已有歌词", fallback)
        return fallback
    }
}

internal fun MusicTrack.requiresQqPlaybackMetadata(): Boolean =
    source == MusicSource.QQ && (durationMs <= 0L || qqPlaybackMid().isBlank())

private class KugouPlaybackAdapter(
    private val api: KugouApiClient,
    private val cookie: () -> String,
) : PlatformPlaybackAdapter {
    override val source = MusicSource.KUGOU

    override suspend fun resolve(track: MusicTrack, quality: AudioQuality): PlaybackSource = withContext(Dispatchers.IO) {
        // 酷狗返回的扩展名和声明码率不总是可信，起播前即校验真实媒体，避免音质按钮长期显示未知。
        api.playbackSource(track, cookie(), quality, inspectMedia = true)
    }

    override suspend fun resolveVerified(track: MusicTrack, quality: AudioQuality): PlaybackSource = withContext(Dispatchers.IO) {
        api.playbackSource(track, cookie(), quality, inspectMedia = true)
    }

    override suspend fun availableQualities(track: MusicTrack, onProgress: (List<AudioQuality>) -> Unit): List<AudioQuality> = withContext(Dispatchers.IO) {
        val candidates = listOf(AudioQuality.STANDARD, AudioQuality.EXHIGH, AudioQuality.LOSSLESS, AudioQuality.HI_RES)
        // 酷狗按档顺序探测，避免起播后同时向服务器请求所有音质。
        val available = mutableListOf<AudioQuality>()
        for (quality in candidates) {
            currentCoroutineContext().ensureActive()
            try {
                val resolved = api.playbackSource(track, cookie(), quality, inspectMedia = true)
                if (resolved.actualQuality == quality && !resolved.trial) {
                    available += quality
                    onProgress(available.toList())
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error.stopsPlaybackFallback()) throw error
            }
        }
        available.toList()
    }

    override suspend fun lyrics(track: MusicTrack): List<TimedLyric> = withContext(Dispatchers.IO) {
        api.lyrics(track, cookie())
    }
}

private class QqPlaybackAdapter(
    private val api: QqApiClient,
    private val cookie: () -> String,
    private val hasVipAccess: () -> Boolean,
    private val guid: () -> String,
    private val artworkAliases: QqArtworkAliasStore,
    private val requestOrigin: QqRequestOrigin,
) : PlatformPlaybackAdapter {
    override val source = MusicSource.QQ
    private val verifiedTracks = java.util.concurrent.ConcurrentHashMap<String, MusicTrack>()

    /** 后加载的详情音乐卡只有 songId，取票前必须先换成真实 songMid。 */
    private suspend fun playbackTrack(track: MusicTrack, includeDolbyMetadata: Boolean = false): MusicTrack {
        val ready = if (track.qqPlaybackMid().isBlank() && track.catalogId.toLongOrNull()?.let { it > 0L } == true) {
            api.enrichTrackMetadata(track, cookie(), hasVipAccess())
        } else track
        if (!includeDolbyMetadata) return ready
        val key = "${ready.providerType}:${ready.remoteId()}"
        verifiedTracks[key]?.let { return ready.mergeQqTrackMetadata(it) }
        // 搜索卡可能只有普通媒体 MID，杜比探测前补齐独立文件标识，并复用本轮详情。
        return try {
            api.enrichTrackMetadata(ready, cookie(), hasVipAccess()).also { verifiedTracks[key] = it }
        } catch (error: Exception) {
            if (error is CancellationException || error.stopsPlaybackFallback()) throw error
            ready
        }
    }

    override suspend fun enrichTrack(track: MusicTrack): MusicTrack = withContext(Dispatchers.IO) {
        val aliased = artworkAliases.apply(track)
        if (!aliased.needsQqMetadataEnrichment()) return@withContext aliased
        val key = "${aliased.providerType}:${aliased.remoteId()}"
        verifiedTracks[key]?.let {
            return@withContext artworkAliases.remember(track, aliased.mergeQqTrackMetadata(it))
        }
        val verified = try {
            api.enrichTrackMetadata(aliased, cookie(), hasVipAccess())
        } catch (error: Throwable) {
            if (error.stopsPlaybackFallback()) throw error
            return@withContext aliased.withConservativeQqQuality()
        }
        verifiedTracks[key] = verified
        artworkAliases.remember(track, aliased.mergeQqTrackMetadata(verified))
    }

    override suspend fun resolve(track: MusicTrack, quality: AudioQuality): PlaybackSource = withContext(Dispatchers.IO) {
        DolbyPlaybackPolicy.requireAllowed(quality)
        val ready = playbackTrack(track, includeDolbyMetadata = quality == AudioQuality.DOLBY)
        api.playbackSource(
            ready,
            cookie(),
            quality,
            inspectMedia = false,
            guid = guid(),
            origin = requestOrigin,
        )
    }

    override suspend fun resolveVerified(track: MusicTrack, quality: AudioQuality): PlaybackSource = withContext(Dispatchers.IO) {
        DolbyPlaybackPolicy.requireAllowed(quality)
        val ready = playbackTrack(track, includeDolbyMetadata = quality == AudioQuality.DOLBY)
        api.playbackSource(ready, cookie(), quality, inspectMedia = true, guid = guid(), origin = requestOrigin)
    }

    override suspend fun availableQualities(track: MusicTrack, onProgress: (List<AudioQuality>) -> Unit): List<AudioQuality> = withContext(Dispatchers.IO) {
        val eligibility = DolbyPlaybackPolicy.eligibility
        val candidates = QQ_AUDIO_QUALITIES.filter { it != AudioQuality.DOLBY || eligibility.allowed }
        qqQualityCandidatesDiagnostic(track.id, candidates, eligibility)
        api.availableQualities(playbackTrack(track, includeDolbyMetadata = AudioQuality.DOLBY in candidates),
            cookie(), candidates, guid(), onProgress)
    }

    override suspend fun lyrics(track: MusicTrack): List<TimedLyric> = withContext(Dispatchers.IO) {
        api.lyrics(playbackTrack(track), cookie())
    }
}

private class NeteasePlaybackAdapter(
    private val api: NeteaseApiClient,
    private val cookie: () -> String,
) : PlatformPlaybackAdapter {
    override val source = MusicSource.NETEASE

    override suspend fun resolve(track: MusicTrack, quality: AudioQuality): PlaybackSource = withContext(Dispatchers.IO) {
        api.playbackSource(track.remoteId(), cookie(), quality)
    }

    override suspend fun availableQualities(track: MusicTrack, onProgress: (List<AudioQuality>) -> Unit): List<AudioQuality> = withContext(Dispatchers.IO) {
        probeNeteaseAvailableQualities(onProgress = onProgress) { quality ->
            api.playbackSource(track.remoteId(), cookie(), quality)
        }
    }

    override suspend fun lyrics(track: MusicTrack): List<TimedLyric> = withContext(Dispatchers.IO) {
        api.lyrics(track.remoteId(), cookie())
    }
}

/** 网易云四档音质（MP3L、MP3H、FLAC、HiRes）探测，高规格 FLAC 流可同时确立普通 FLAC 与 HiRes 档位。 */
internal suspend fun probeNeteaseAvailableQualities(
    candidates: List<AudioQuality> = NETEASE_AUDIO_QUALITIES,
    onProgress: (List<AudioQuality>) -> Unit = {},
    resolve: suspend (AudioQuality) -> PlaybackSource,
): List<AudioQuality> = supervisorScope {
    val verified = mutableSetOf<AudioQuality>()
    val progressLock = Any()
    candidates.map { quality ->
        async {
            try {
                val source = resolve(quality)
                if (!source.trial && isNeteaseQualitySatisfied(quality, source.actualQuality)) {
                    synchronized(progressLock) {
                        verified += quality
                        onProgress(candidates.filter { it in verified })
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (error.stopsPlaybackFallback()) throw error
            }
        }
    }.awaitAll()
    candidates.filter { it in verified }
}

internal fun isNeteaseQualitySatisfied(requested: AudioQuality, actual: AudioQuality): Boolean = when (requested) {
    AudioQuality.STANDARD -> actual == AudioQuality.STANDARD
    AudioQuality.HIGHER, AudioQuality.EXHIGH -> actual in setOf(AudioQuality.HIGHER, AudioQuality.EXHIGH)
    AudioQuality.LOSSLESS -> actual in setOf(AudioQuality.LOSSLESS, AudioQuality.HI_RES)
    AudioQuality.HI_RES -> actual == AudioQuality.HI_RES
    else -> actual == requested
}
