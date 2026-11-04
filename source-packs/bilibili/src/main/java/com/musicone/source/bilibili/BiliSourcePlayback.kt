package com.musicone.source.bilibili

import android.media.MediaCodecList
import com.musicone.source.sdk.*
import org.json.JSONObject

internal class BiliSourcePlayback(private val state: () -> BiliPackageState) : SourceMediaPlayback, SourcePlaybackBehavior, SourcePlayerPresentation {
    override fun qualityLabel(qualityId: String): String? = if (qualityId == "STANDARD") "普通" else null
    override fun qualityCompactLabel(qualityId: String): String? = if (qualityId == "STANDARD") "Norm" else null
    override val pendingAudioDescription = "正在读取音轨规格…"
    override val renewOnPlayerErrorLimit = 1

    override val defaultQualityId = "HI_RES"
    override val followsMainQualityPreference = true
    override fun fallbackQualityIds(preferredId: String): List<String> = when (preferredId) {
        "DOLBY" -> listOf("DOLBY", "HI_RES", "STANDARD")
        "STANDARD", "EXHIGH", "HIGHER" -> listOf("STANDARD")
        else -> listOf("HI_RES", "STANDARD")
    }
    override fun normalizePreferredQuality(savedQualityId: String?, mainQualityId: String?): String {
        if (mainQualityId != null) return if (mainQualityId in setOf("LOSSLESS", "HI_RES", "DOLBY")) "DOLBY" else "STANDARD"
        return when (savedQualityId) { "STANDARD" -> "STANDARD"; "DOLBY" -> "DOLBY"; else -> "HI_RES" }
    }
    override suspend fun enrich(track: SourceTrackDetails): SourceTrackDetails {
        if (BiliSourceMapping.cid(track) > 0 && track.durationMs > 0) return track
        return state().request { captured ->
            val details = state().api.getVideoDetails(BiliSourceMapping.bvid(track), captured.session?.cookie.orEmpty())
            val cid = BiliSourceMapping.cid(track)
            val part = if (cid > 0) details.pages.firstOrNull { it.cid == cid } else details.pages.firstOrNull()
            track.copy(durationMs = track.durationMs.takeIf { it > 0 } ?: part?.durationMs ?: details.durationSec * 1000,
                album = track.album.ifBlank { details.title },
                artwork = if (track.artwork.url.isNullOrBlank()) SourceArtwork(details.cover) else track.artwork,
                track = track.track.copy(opaqueData = track.track.opaqueData + mapOf(
                    "bilibiliBvid" to details.bvid, "bilibiliCid" to (part?.cid ?: cid).toString())),
                access = track.access.copy(playable = part != null, reason = if (part == null) "此分 P 已不存在" else null))
        }
    }
    override suspend fun qualities(track: SourceTrackDetails): List<SourceAudioQuality> = state().request { captured ->
        val (bvid, cid) = coordinates(track, captured)
        val audio = state().api.getAudioStreams(bvid, cid, captured.session?.cookie.orEmpty())
        buildList {
            if (audio.defaultAudio != null) add(SourceAudioQuality("STANDARD", "普通", audio.defaultAudio.mimeType))
            if (audio.hiResAudio != null) add(SourceAudioQuality("HI_RES", "Hi-Res", audio.hiResAudio.mimeType))
            if (audio.dolbyAudio != null) add(SourceAudioQuality("DOLBY", "Dolby全景", audio.dolbyAudio.mimeType))
        }
    }
    override suspend fun resolve(request: SourcePlaybackRequest): SourcePlaybackResource = state().request { captured ->
        val (bvid, cid) = coordinates(request.track, captured)
        val audio = state().api.getAudioStreams(bvid, cid, captured.session?.cookie.orEmpty())
        val capabilities = request.deviceCapabilities
        val usbExclusive = "usb-exclusive" in capabilities
        val decoder = "dolby-decoder" in capabilities || detectDolbyDecoder()
        val unlocked = "dolby-unlocked" in capabilities
        val automatic = request.automatic
        val preferred = request.qualityId?.uppercase()?.replace('-', '_') ?: "HI_RES"
        val (quality, stream) = selectBiliAudio(audio, preferred, automatic, decoder, unlocked, usbExclusive)
        val manifest = BiliDashBuilder.buildAudioOnlyManifest(stream, audio.durationMs, quality == "DOLBY")
        val headers = state().webSession.headers(captured.session?.cookie.orEmpty(), "https://www.bilibili.com/video/$bvid")
        val cache = BiliDashBuilder.buildCacheKey(captured.session?.mid.orEmpty(), bvid, cid, stream.id, stream.codecs)
        val urls = (listOf(stream.baseUrl) + stream.backupUrls).filter(String::isNotBlank).distinct()
        val recovery = SourcePayload("bilibili.audio", 1, JSONObject().put("bvid", bvid).put("cid", cid)
            .put("streamId", stream.id).put("codecs", stream.codecs).put("initializationRange", stream.initializationRange)
            .put("indexRange", stream.indexRange).put("actualFormat", biliStreamCodecLabel(stream, quality == "DOLBY"))
            .put("bitRate", stream.bandwidth).toString())
        SourcePlaybackResource(
            PlaybackAddress(BiliDashBuilder.toDataUri(manifest), headers, stream.backupUrls, "application/dash+xml", cache,
                expiresAtEpochMs = System.currentTimeMillis() + 90 * 60_000L),
            quality, stream.codecs.ifBlank { stream.mimeType }, inlineManifest = manifest,
            segments = urls.map { SourceMediaSegment(it, headers) }, recovery = recovery, accountId = captured.session?.mid,
            bitRate = stream.bandwidth.toInt(), actualFormat = biliStreamCodecLabel(stream, quality == "DOLBY"),
            trial = false, verificationPending = false
        )
    }
    override suspend fun recover(request: SourcePlaybackRequest, previous: SourcePlaybackResource): SourcePlaybackResource = resolve(request)
    override suspend fun availability(tracks: List<SourceTrackDetails>): List<TrackAccessInfo> = state().request { captured ->
        tracks.map { track ->
            val details = state().api.getVideoDetails(BiliSourceMapping.bvid(track), captured.session?.cookie.orEmpty())
            val cid = BiliSourceMapping.cid(track)
            val valid = details.pages.isNotEmpty() && (cid <= 0 || details.pages.any { it.cid == cid })
            TrackAccessInfo(playable = valid, reason = if (valid) null else "此分 P 已不存在")
        }
    }
    private fun coordinates(track: SourceTrackDetails, account: BiliAccountRequest): Pair<String, Long> {
        require(track.track.identity.sourceId == BILI_SOURCE_ID)
        val bvid = BiliSourceMapping.bvid(track)
        val current = BiliSourceMapping.cid(track)
        if (current > 0) return bvid to current
        val pages = state().api.getVideoPages(bvid, account.session?.cookie.orEmpty())
        return bvid to requireNotNull(pages.firstOrNull()) { "视频分 P 列表为空" }.cid
    }
    private fun detectDolbyDecoder(): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info -> !info.isEncoder &&
            info.supportedTypes.any { it.lowercase() in setOf("audio/eac3", "audio/eac3-joc", "audio/ac4") } }
    }.getOrDefault(false)
}
