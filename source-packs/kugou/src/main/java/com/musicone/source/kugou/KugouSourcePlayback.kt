package com.musicone.source.kugou

import com.musicone.source.sdk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.URI

internal class KugouSourcePlayback(private val state: () -> KugouPackageState) : SourceMediaPlayback, SourceLyricProvider, SourcePlaybackBehavior, SourceLyricBehavior, SourcePlaybackSessionProvider {
    private val trackAccess = KugouTrackAccessResolver()
    override val playbackSessions: SourcePlaybackSessionObserver by lazy {
        val owner = state()
        KugouListeningReporter({ owner.capture().also { requireKugouAuthenticatedRequest(it.cookie, owner.account()?.userId) } }, owner::check,
            KugouListeningClient(KugouListeningCscc({ KugouListeningDevice.read(owner.environment.context) })))
    }
    override val skipOnPlayerError = false
    override val pauseOnFailure = true
    override val preserveVerbatimFallback = true

    override val parserVersion get() = CURRENT_KUGOU_LYRIC_PARSER_VERSION
    override fun fallbackQualityIds(preferredId: String): List<String> =
        super<SourceMediaPlayback>.fallbackQualityIds(if (preferredId == "DOLBY") "HI_RES" else preferredId)
    override fun normalizePreferredQuality(savedQualityId: String?, mainQualityId: String?): String = when (savedQualityId) {
        "HIGHER" -> "EXHIGH"; "DOLBY" -> "LOSSLESS"; else -> savedQualityId ?: defaultQualityId
    }
    override suspend fun qualities(track: SourceTrackDetails): List<SourceAudioQuality> = qualities(track) {}
    // 逐档探测较慢，每确认一档就回报，菜单不必等四档全部结束。
    override suspend fun qualities(track: SourceTrackDetails, onProgress: (List<SourceAudioQuality>) -> Unit): List<SourceAudioQuality> = state().request { session ->
        val value = KugouSourceMapping.track(track)
        buildList {
            for (quality in listOf(AudioQuality.STANDARD, AudioQuality.EXHIGH, AudioQuality.LOSSLESS, AudioQuality.HI_RES)) {
                currentCoroutineContext().ensureActive()
                try {
                    val result = state().api.playbackSource(value, session.cookie, quality, inspectMedia = true)
                    if (result.actualQuality == quality && !result.trial) {
                        add(SourceAudioQuality(quality.name, quality.label))
                        onProgress(toList())
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { if (failure.stopsPlaybackFallback()) throw failure }
            }
        }
    }
    override suspend fun resolve(request: SourcePlaybackRequest): SourcePlaybackResource = state().request { session ->
        val track = KugouSourceMapping.track(request.track)
        val requested = runCatching { AudioQuality.valueOf(request.qualityId.orEmpty().uppercase()) }.getOrDefault(AudioQuality.HI_RES)
            .let { if (it == AudioQuality.HIGHER) AudioQuality.EXHIGH else it }
        require(requested != AudioQuality.DOLBY) { "酷狗音乐不支持 Dolby 音质" }
        val candidates = if (request.automatic) listOf(AudioQuality.HI_RES, AudioQuality.LOSSLESS, AudioQuality.EXHIGH, AudioQuality.STANDARD)
            .filter { it.ordinal <= requested.ordinal } else listOf(requested)
        var trial: PlaybackSource? = null
        var failure: Exception? = null
        var resolved: PlaybackSource? = null
        for (quality in candidates) {
            currentCoroutineContext().ensureActive()
            try {
                val value = state().api.playbackSource(track, session.cookie, quality, inspectMedia = true)
                if (value.trial && request.automatic) { if (trial == null) trial = value } else { resolved = value; break }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (error.stopsPlaybackFallback()) throw error; failure = error }
        }
        val result = resolved ?: trial ?: throw (failure ?: PlatformApiException("歌曲暂无可用音源"))
        val account = state().account()?.userId
        val cache = if (result.trial) "uncached-trial:KUGOU|${track.id}" else "KUGOU:${account ?: "guest"}|${track.id}|${URI.create(result.url).path.orEmpty()}"
        SourcePlaybackResource(PlaybackAddress(result.url, result.requestHeaders, mimeType = result.mimeType, cacheIdentity = cache,
            expiresAtEpochMs = System.currentTimeMillis() + 18 * 60_000L),
            result.actualQuality.name, result.format, accountId = account, bitRate = result.bitRate,
            actualFormat = result.actualFormat, trial = result.trial, verificationPending = result.verificationPending)
    }
    override suspend fun recover(request: SourcePlaybackRequest, previous: SourcePlaybackResource): SourcePlaybackResource = resolve(request)
    override suspend fun availability(tracks: List<SourceTrackDetails>): List<TrackAccessInfo> = state().request { session ->
        val vip = state().verifiedVipAccess(session)
        tracks.map { track ->
            val access = trackAccess.access(track, session.cookie, vip)
            currentCoroutineContext().ensureActive(); state().check(session)
            try {
                state().api.playbackSource(KugouSourceMapping.track(track), session.cookie, AudioQuality.STANDARD, inspectMedia = true)
                access.copy(playable = true, reason = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: TrackUnavailableException) { access.copy(playable = false, reason = failure.message) }
            catch (failure: Exception) {
                if (failure.stopsPlaybackFallback()) throw failure
                // 媒体探测临时失败不撤销刚确认的收费标记，也不改写已有可播放状态。
                access
            }
        }
    }
    override suspend fun lyrics(track: SourceTrackDetails): SourceLyrics? = state().request { session ->
        state().api.lyrics(KugouSourceMapping.track(track), session.cookie).takeIf { it.isNotEmpty() }?.let(KugouSourceMapping::lyrics)
    }
    override fun timing(track: SourceTrackDetails) = SourceLyricTiming(185, 115)
}
