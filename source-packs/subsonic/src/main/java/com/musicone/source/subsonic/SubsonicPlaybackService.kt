package com.musicone.source.subsonic

import com.musicone.source.sdk.*
import com.musicone.source.subsonic.internal.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private const val QUALITY_ORIGINAL = "original"
private const val QUALITY_MP3_320 = "mp3-320"
private const val QUALITY_MP3_128 = "mp3-128"

/** 原文件不经转码；两个 MP3 档由服务器实时转码，服务器没配置转码时会按原文件返回。 */
internal val SUBSONIC_QUALITY_CATALOG = listOf(
    SourceQualityDefinition(QUALITY_ORIGINAL, "原始音质", "原始", "按服务器上的原文件播放，不转码"),
    SourceQualityDefinition(QUALITY_MP3_320, "MP3 320k", "320k", "由服务器实时转码，需服务器已配置转码", "EXHIGH"),
    SourceQualityDefinition(QUALITY_MP3_128, "MP3 128k", "128k", "由服务器实时转码，适合移动网络", "STANDARD"),
)

/** 曲目可用档位；原始档带上真实格式和码率，转码档不猜测实际规格。 */
internal fun subsonicTrackQualities(suffix: String, bitRate: Int): List<SourceAudioQuality> {
    val format = suffix.uppercase(Locale.ROOT)
    val original = listOf(format.takeIf(String::isNotBlank), bitRate.takeIf { it > 0 }?.let { "${it}kbps" })
        .filterNotNull().joinToString(" · ")
    return listOf(
        SourceAudioQuality(QUALITY_ORIGINAL, if (original.isBlank()) "原始音质" else "原始 · $original", subsonicMimeType(suffix, "", bitRate)),
        SourceAudioQuality(QUALITY_MP3_320, "MP3 320k", "audio/mpeg"),
        SourceAudioQuality(QUALITY_MP3_128, "MP3 128k", "audio/mpeg"),
    )
}

/**
 * 宿主按格式提示把 DSD、APE、WavPack、ALAC 交给原生解码，这几种按后缀给出明确类型，不用服务器的笼统类型。
 * ALAC 与 AAC 同为 m4a，无损码率远高于 AAC 上限，以码率区分。
 */
internal fun subsonicMimeType(suffix: String, contentType: String, bitRate: Int): String? = when (suffix.lowercase(Locale.ROOT)) {
    "dsf" -> "audio/x-dsf"; "dff" -> "audio/x-dff"; "ape" -> "audio/x-ape"; "wv" -> "audio/x-wavpack"; "alac" -> "audio/alac"
    "m4a", "mp4" -> if (bitRate >= ALAC_MIN_BIT_RATE) "audio/alac" else contentType.ifBlank { "audio/mp4" }
    else -> contentType.takeIf(String::isNotBlank) ?: when (suffix.lowercase(Locale.ROOT)) {
        "mp3" -> "audio/mpeg"; "flac" -> "audio/flac"; "aac" -> "audio/mp4"
        "ogg", "oga" -> "audio/ogg"; "opus" -> "audio/opus"; "wav" -> "audio/wav"
        else -> null
    }
}

private const val ALAC_MIN_BIT_RATE = 600

/** 播放取址、音质、计入播放次数和随机电台。媒体地址只靠 URL 里的认证参数，不需要额外请求头。 */
internal class SubsonicPlaybackService(
    private val requests: SubsonicRequests,
    private val client: SubsonicClient,
    private val urls: SubsonicArtworkUrls,
    private val home: SubsonicHomeFeed,
) : SourceMediaPlayback, SourceQualityCatalog, SourcePlaybackSessionProvider, SourceRadio {
    override val qualityCatalog = SUBSONIC_QUALITY_CATALOG
    override val defaultCatalogQualityId = QUALITY_ORIGINAL
    override val defaultQualityId = QUALITY_ORIGINAL

    override fun catalogFallbackQualityIds(preferredId: String): List<String> =
        listOf(preferredId, QUALITY_ORIGINAL).filter { id -> qualityCatalog.any { it.id == id } }.distinct()

    override fun normalizePreferredQuality(savedQualityId: String?, mainQualityId: String?): String =
        savedQualityId?.takeIf { id -> qualityCatalog.any { it.id == id } } ?: QUALITY_ORIGINAL

    override fun fallbackQualityIds(preferredId: String): List<String> = catalogFallbackQualityIds(preferredId)

    override suspend fun qualities(track: SourceTrackDetails): List<SourceAudioQuality> {
        val ref = track.subsonicRef()
        return subsonicTrackQualities(ref.suffix, ref.bitRate)
    }

    override suspend fun resolve(request: SourcePlaybackRequest): SourcePlaybackResource {
        val ref = request.track.subsonicRef()
        val quality = request.qualityId?.takeIf { id -> qualityCatalog.any { it.id == id } } ?: QUALITY_ORIGINAL
        return requests.forServer(ref.serverKey) { server ->
            val parameters = buildList {
                add("id" to ref.id)
                when (quality) {
                    QUALITY_ORIGINAL -> add("format" to "raw")
                    else -> {
                        add("format" to "mp3")
                        add("maxBitRate" to if (quality == QUALITY_MP3_320) "320" else "128")
                        // 转码流没有原始长度，请服务器估算以便播放器显示进度。
                        add("estimateContentLength" to "true")
                    }
                }
            }
            val original = quality == QUALITY_ORIGINAL
            val format = if (original) ref.suffix.ifBlank { "audio" } else "mp3"
            SourcePlaybackResource(
                PlaybackAddress(client.url(server, "stream", parameters),
                    mimeType = if (original) subsonicMimeType(ref.suffix, ref.contentType, ref.bitRate) else "audio/mpeg",
                    cacheIdentity = "subsonic|${server.key}|${ref.id}|$quality"),
                quality, format, accountId = server.key,
                bitRate = if (original) ref.bitRate else if (quality == QUALITY_MP3_320) 320 else 128,
                actualFormat = format,
            )
        }
    }

    override suspend fun recover(request: SourcePlaybackRequest, previous: SourcePlaybackResource): SourcePlaybackResource = resolve(request)

    /** 不逐首查询服务器；曲目不存在时由播放失败处理。 */
    override suspend fun availability(tracks: List<SourceTrackDetails>): List<TrackAccessInfo> = tracks.map { TrackAccessInfo() }

    // ---- 计入播放次数 ----

    private val submitted = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 200
    }

    /** 听满一半（最多 4 分钟）或自然播完才提交一次，和常见的 scrobble 规则一致。 */
    override val playbackSessions: SourcePlaybackSessionObserver = SourcePlaybackSessionObserver { event ->
        val due = when (event.type) {
            SourcePlaybackSessionEventType.END -> event.endReason == SourcePlaybackSessionEndReason.COMPLETED ||
                event.playedMs >= scrobbleThreshold(event.track.durationMs)
            SourcePlaybackSessionEventType.PROGRESS, SourcePlaybackSessionEventType.PAUSE ->
                event.playedMs >= scrobbleThreshold(event.track.durationMs)
            else -> false
        }
        if (!due || event.track.durationMs in 1 until 30_000) return@SourcePlaybackSessionObserver
        val first = synchronized(submitted) { submitted.put(event.sessionId, true) == null }
        if (!first) return@SourcePlaybackSessionObserver
        val ref = event.track.subsonicRef()
        val startedAt = event.occurredAtMs - event.playedMs
        try {
            requests.forServer(ref.serverKey) { server ->
                client.get(server, "scrobble", listOf("id" to ref.id, "submission" to "true", "time" to startedAt.toString())); Unit
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { /* 尽力上报，不影响播放 */ }
    }

    private fun scrobbleThreshold(durationMs: Long): Long =
        if (durationMs > 0) minOf(durationMs / 2, 240_000L) else 240_000L

    // ---- 随机电台 ----

    /** 宿主每次新电台都传 null，没有起点歌曲，所以电台是整个曲库的随机播放，排除本轮已播过的歌。 */
    override suspend fun next(previous: SourcePayload?): SourceRadioBatch = requests.current { server ->
        val seen = previous?.takeIf { it.format == RADIO_FORMAT && it.encoded.isNotBlank() }?.let { payload ->
            val value = JSONObject(payload.encoded)
            if (value.optString("server") != server.key) emptyList()
            else value.optJSONArray("ids")?.let { array -> (0 until array.length()).map(array::optString) }.orEmpty()
        }.orEmpty()
        val seenSet = seen.toHashSet()
        val songs = home.randomSongs(server, RADIO_BATCH).filter { it.id !in seenSet }
        val remembered = (seen + songs.map { it.id }).takeLast(RADIO_MEMORY)
        SourceRadioBatch(songs.map { it.details(server, urls) },
            SourcePayload(RADIO_FORMAT, 1, JSONObject().put("server", server.key).put("ids", JSONArray(remembered)).toString()))
    }

    override suspend fun feedback(track: SourceTrackDetails, action: String) {
        throw UnsupportedOperationException("随机电台不使用反馈")
    }

    private companion object {
        const val RADIO_FORMAT = "subsonic.radio"
        const val RADIO_BATCH = 20
        const val RADIO_MEMORY = 300
    }
}
