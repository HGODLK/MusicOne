package com.musicone.demo

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

internal interface PlatformPlaybackAdapter {
    val source: MusicSource
    suspend fun enrichTrack(track: MusicTrack): MusicTrack = track
    suspend fun resolve(track: MusicTrack, quality: AudioQuality): PlaybackSource
    suspend fun resolveVerified(track: MusicTrack, quality: AudioQuality): PlaybackSource = resolve(track, quality)
    suspend fun availableQualities(track: MusicTrack, onProgress: (List<AudioQuality>) -> Unit): List<AudioQuality>
    suspend fun lyrics(track: MusicTrack): List<TimedLyric>
}

internal class PlatformPlaybackResolver(
    context: Context,
    qqCacheRequests: Boolean = false,
    qqRequestOrigin: QqRequestOrigin = if (qqCacheRequests) QqRequestOrigin.BATCH_CACHE else QqRequestOrigin.PLAYBACK,
) {
    private val offline = OfflineMusicStore(context.applicationContext)
    private val cachedLyrics = CachedLyricsStore(context.applicationContext)
    private val preferences = PlatformPreferences(context)
    private val audioCapabilities = DeviceAudioCapabilities.detect()
    private val qqArtworkAliases = QqArtworkAliasStore(context)
    private val adapters: Map<MusicSource, PlatformPlaybackAdapter> = listOf(
        NeteasePlaybackAdapter(NeteaseApiClient()) { preferences.credential(MusicSource.NETEASE) },
        QqPlaybackAdapter(
            QqApiClient(),
            { preferences.credential(MusicSource.QQ) },
            { preferences.readSession(MusicSource.QQ).account?.hasVipAccess == true },
            { qqStableGuid(preferences.readSession(MusicSource.QQ).deviceId) },
            audioCapabilities.supportsDolbyAtmos,
            qqArtworkAliases,
            qqRequestOrigin,
        ),
        KugouPlaybackAdapter(KugouApiClient()) { preferences.credential(MusicSource.KUGOU) },
    ).associateBy(PlatformPlaybackAdapter::source)

    suspend fun resolve(
        track: MusicTrack,
        preferredQuality: AudioQuality,
        onlineSettleDelayMs: Long = 0L,
    ): ResolvedPlayback {
        val localLyrics = track.lyrics.ifEmpty { cachedLyrics.read(track) }
        val readyTrack = track.copy(lyrics = localLyrics)
        // 完整缓存立即交付；在线取票等待切歌动作稳定，取消期间不产生已经来不及撤回的 HTTP 请求。
        offline.resolve(readyTrack, preferredQuality, allowOtherQuality = true)?.let { return it }
        if (onlineSettleDelayMs > 0L) delay(onlineSettleDelayMs)
        return resolveOnline(readyTrack, preferredQuality, verifyMedia = false)
    }

    suspend fun resolvePreferred(track: MusicTrack, preferredQuality: AudioQuality): ResolvedPlayback =
        offline.resolve(track, preferredQuality, allowOtherQuality = false)
            ?: resolveOnline(track, preferredQuality, verifyMedia = true)

    private suspend fun resolveOnline(track: MusicTrack, preferredQuality: AudioQuality, verifyMedia: Boolean): ResolvedPlayback {
        if (!offline.hasNetwork()) throw PlatformApiException("这首歌曲尚未完整缓存，请联网后缓存")
        if (!track.playable) throw PlatformApiException(track.unavailableReason ?: "当前歌曲暂不可播放")
        val adapter = adapters[track.source] ?: throw PlatformApiException("${track.source.label}尚未接入播放")
        val aliasedTrack = qqArtworkAliases.apply(track)
        // 音乐杂志详情卡可能只有数字 songId 和零时长。取票本来就要补齐 MID，
        // 这里把同一次补全结果继续交给播放器，供待验证 FLAC 按真实时长完成确认。
        val resolvedTrack = if (aliasedTrack.requiresQqPlaybackMetadata()) {
            adapter.enrichTrack(aliasedTrack)
        } else {
            aliasedTrack
        }
        var trialFallback: PlaybackSource? = null
        val source = preferredQuality.fallbackCandidates(track.source).firstNotNullOfOrNull { quality ->
            try {
                val candidate = if (verifyMedia) adapter.resolveVerified(resolvedTrack, quality)
                    else adapter.resolve(resolvedTrack, quality)
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
                null
            }
        } ?: trialFallback ?: throw PlatformApiException("歌曲暂无可用播放地址")
        return ResolvedPlayback(resolvedTrack.copy(previewUrl = source.url), source)
            .also { offline.remember(it, preferredQuality) }
    }

    suspend fun enrichMetadata(track: MusicTrack): MusicTrack =
        if (offline.hasNetwork()) adapters[track.source]?.enrichTrack(track) ?: track else track

    suspend fun resolveExact(track: MusicTrack, quality: AudioQuality): PlaybackSource {
        offline.resolve(track, quality, allowOtherQuality = false)?.let { return it.source }
        if (!offline.hasNetwork()) throw PlatformApiException("此音质尚未缓存")
        val adapter = adapters[track.source] ?: throw PlatformApiException("${track.source.label}尚未接入播放")
        val source = adapter.resolveVerified(track, quality)
        if (source.trial) {
            throw PlatformApiException("当前歌曲只返回${quality.displayLabel(track.source)}试听音源")
        }
        if (source.actualQuality != quality) {
            throw PlatformApiException("平台未返回${quality.displayLabel(track.source)}完整音源")
        }
        offline.remember(ResolvedPlayback(track.copy(previewUrl = source.url), source))
        return source
    }

    suspend fun availableQualities(
        track: MusicTrack,
        onProgress: (List<AudioQuality>) -> Unit = {},
    ): List<AudioQuality> {
        if (!offline.hasNetwork()) return AudioQuality.entries.filter {
            offline.resolve(track, it, allowOtherQuality = false) != null
        }
        val adapter = adapters[track.source] ?: return emptyList()
        return adapter.availableQualities(adapter.enrichTrack(track), onProgress)
    }

    suspend fun lyrics(track: MusicTrack): List<TimedLyric> {
        if (track.lyrics.isNotEmpty()) {
            cachedLyrics.save(track, track.lyrics)
            return track.lyrics
        }
        cachedLyrics.read(track).takeIf { it.isNotEmpty() }?.let { return it }
        if (!offline.hasNetwork()) return emptyList()
        return adapters[track.source]?.lyrics(track).orEmpty().also { cachedLyrics.save(track, it) }
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
            .filter { track.kugouHashFor(it) != null }
        probeAvailableQualities(candidates) { quality ->
            api.playbackSource(track, cookie(), quality, inspectMedia = true)
        }
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
    private val supportsDolbyAtmos: Boolean,
    private val artworkAliases: QqArtworkAliasStore,
    private val requestOrigin: QqRequestOrigin,
) : PlatformPlaybackAdapter {
    override val source = MusicSource.QQ
    private val verifiedTracks = java.util.concurrent.ConcurrentHashMap<String, MusicTrack>()

    /** 后加载的详情音乐卡只有 songId，取票前必须先换成真实 songMid。 */
    private suspend fun playbackTrack(track: MusicTrack): MusicTrack {
        if (track.qqPlaybackMid().isNotBlank()) return track
        if (track.catalogId.toLongOrNull()?.let { it > 0L } != true) return track
        return api.enrichTrackMetadata(track, cookie(), hasVipAccess())
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
        if (quality == AudioQuality.DOLBY && !supportsDolbyAtmos) {
            throw PlatformApiException("当前设备不支持 Dolby 全景声")
        }
        val ready = playbackTrack(track)
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
        if (quality == AudioQuality.DOLBY && !supportsDolbyAtmos) {
            throw PlatformApiException("当前设备不支持 Dolby 全景声")
        }
        val ready = playbackTrack(track)
        api.playbackSource(ready, cookie(), quality, inspectMedia = true, guid = guid(), origin = requestOrigin)
    }

    override suspend fun availableQualities(track: MusicTrack, onProgress: (List<AudioQuality>) -> Unit): List<AudioQuality> = withContext(Dispatchers.IO) {
        val candidates = QQ_AUDIO_QUALITIES.filter { it != AudioQuality.DOLBY || supportsDolbyAtmos }
        api.availableQualities(playbackTrack(track), cookie(), candidates, guid(), onProgress)
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
        probeAvailableQualities { quality -> api.playbackSource(track.remoteId(), cookie(), quality) }
    }

    override suspend fun lyrics(track: MusicTrack): List<TimedLyric> = withContext(Dispatchers.IO) {
        api.lyrics(track.remoteId(), cookie())
    }
}

private suspend fun probeAvailableQualities(
    candidates: List<AudioQuality> = STANDARD_AUDIO_QUALITIES,
    resolve: suspend (AudioQuality) -> PlaybackSource,
): List<AudioQuality> = supervisorScope {
    candidates.map { quality ->
        async {
            try {
                val source = resolve(quality)
                quality.takeIf { source.actualQuality == quality && !source.trial }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (error.stopsPlaybackFallback()) throw error
                null
            }
        }
    }.awaitAll().filterNotNull()
}
