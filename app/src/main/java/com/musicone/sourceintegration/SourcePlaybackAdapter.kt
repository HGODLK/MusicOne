package com.musicone.sourceintegration

import android.content.Context
import android.util.Base64
import com.musicone.*
import com.musicone.source.sdk.*

/** 每次操作取得仍有效的包实例，不持有已删除包的业务接口。 */
internal class SourcePlaybackAdapter(private val context: Context, override val source: MusicSource,
    private val origin: PlaybackRequestOrigin = PlaybackRequestOrigin.PLAYBACK) : PlatformPlaybackAdapter {
    private suspend fun entry(): SourceEntry {
        val packages = SourcePackageController.get(context)
        packages.awaitStartup()
        return packages.running(source.id)?.entry
            ?: throw PlatformApiException("${source.label}音源未安装或初始化失败")
    }
    private suspend fun playback() = entry().mediaPlayback ?: throw PlatformApiException("${source.label}未提供播放能力")
    override suspend fun enrichTrack(track: MusicTrack) = playback().enrich(track.toSourceTrack()).toMusicTrack()
    override suspend fun resolve(track: MusicTrack, quality: AudioQuality) = resolveResource(track, quality, false)
    override suspend fun resolveAutomatic(track: MusicTrack, quality: AudioQuality, verifyMedia: Boolean) = resolveResource(track, quality, true)
    override suspend fun recover(track: MusicTrack, quality: AudioQuality, previous: PlaybackSource): PlaybackSource {
        val resource = previous.sourceResource ?: throw PlatformApiException("播放恢复资料已失效，请重试")
        val recovered = playback().recover(SourcePlaybackRequest(track.toSourceTrack(), quality.qualityId), resource)
        val key = if (recovered.trial) recovered.address.cacheIdentity
            else MusicDiskCache.get(context).register(recovered.address.cacheIdentity, track)
        return recovered.toPlaybackSource(quality, key, source).also { resolved ->
            if (resolved.actualQuality.isCustom) {
                if (sourceCatalogQualities(source)?.none { it.qualityId == recovered.qualityId } != false)
                    throw PlatformApiException("音源恢复的音质未列入目录")
                DolbyPlaybackPolicy.requireAllowed(resolved)
            }
        }
    }
    private suspend fun resolveResource(track: MusicTrack, quality: AudioQuality, automatic: Boolean): PlaybackSource {
        val policy = DolbyPlaybackPolicy.eligibility
        val capabilities = buildSet {
            if (policy.decoderSupported) add("dolby-decoder")
            if (policy.unlocked) add("dolby-unlocked")
            if (policy.usbExclusive) add("usb-exclusive")
        }
        val resource = playback().resolve(SourcePlaybackRequest(track.toSourceTrack(), quality.qualityId,
            deviceCapabilities = capabilities, automatic = automatic, purpose = when (origin) {
                PlaybackRequestOrigin.PLAYBACK -> SourcePlaybackPurpose.PLAYBACK
                PlaybackRequestOrigin.PRELOAD -> SourcePlaybackPurpose.PREFETCH
                PlaybackRequestOrigin.BATCH_CACHE -> SourcePlaybackPurpose.CACHE
            }))
        val key = if (resource.trial) resource.address.cacheIdentity
            else MusicDiskCache.get(context).register(resource.address.cacheIdentity, track)
        return resource.toPlaybackSource(quality, key, source)
    }
    override suspend fun availableQualities(track: MusicTrack, onProgress: (List<AudioQuality>) -> Unit): List<AudioQuality> {
        fun mapped(rows: List<SourceAudioQuality>) = rows.filter { it.available }.mapNotNull { row ->
            val catalog = sourceCatalogQualities(source)
            if (catalog != null) catalog.firstOrNull { it.qualityId == row.id } else sourceAudioQuality(row.id)
        }.distinct()
        // 包逐档回报已确认档位，空列表不覆盖界面上已有的当前档。
        return mapped(playback().qualities(track.toSourceTrack()) { rows -> mapped(rows).takeIf { it.isNotEmpty() }?.let(onProgress) })
            .also(onProgress)
    }
    override suspend fun lyrics(track: MusicTrack): List<TimedLyric> =
        entry().lyricProvider?.lyrics(track.toSourceTrack())?.toTimedLyrics().orEmpty()
}

internal fun sourceAudioQuality(id: String): AudioQuality? = AudioQuality.entries.firstOrNull {
    it.name.equals(id, true) || it.providerLevel.equals(id, true)
}

internal fun sourceAccountState(context: Context, source: MusicSource): SourceAccountState? =
    SourcePackageController.get(context).running(source.id)?.entry?.authentication?.currentAccount()

internal fun sourceCacheNamespace(context: Context, source: MusicSource): String =
    "${source.name}:${sourceAccountState(context, source)?.account?.id ?: "guest"}"

internal fun SourcePlaybackResource.toPlaybackSource(requested: AudioQuality, key: String = address.cacheIdentity, source: MusicSource? = requested.sourceId?.let(MusicSource::fromId)): PlaybackSource {
    val url = inlineManifest?.let { "data:application/dash+xml;base64," + Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) }
        ?: address.url
    SupplementalPlaybackRequests.register(url, address.headers, key)
    address.alternatives.forEach { SupplementalPlaybackRequests.register(it, address.headers, key) }
    segments.forEach { segment ->
        SupplementalPlaybackRequests.register(segment.url, segment.headers, key)
        segment.alternatives.forEach { SupplementalPlaybackRequests.register(it, segment.headers, key) }
    }
    val catalog = source?.let(::sourceQualityCatalog)
    val actual = if (catalog != null && source != null) sourceAudioQuality(qualityId, source)
        ?: AudioQuality.custom(source.id, qualityId, "未知音质", "未知")
    else sourceAudioQuality(qualityId) ?: requested
    return PlaybackSource(url, requested, actual, bitRate, format, trial,
        actualFormat, verificationPending, address.headers, key, address.mimeType ?: if (inlineManifest != null) "application/dash+xml" else null,
        address.expiresAtEpochMs, this)
        .also(SupplementalPlaybackRequests::remember)
}
