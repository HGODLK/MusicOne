package com.musicone.source.sdk

data class SourceLyricWord(val startMs: Long, val durationMs: Long, val text: String)
data class SourceLyricLine(val startMs: Long, val text: String, val words: List<SourceLyricWord> = emptyList(),
    val translation: String? = null, val transliteration: String? = null, val parserVersion: Int = 0)
data class SourceLyrics(val lines: List<SourceLyricLine>, val plainText: String? = null)
data class SourceLyricTiming(val lineLeadMs: Long = 0, val wordLeadMs: Long = 0)
interface SourceLyricProvider {
    val parserVersion: Int get() = 0
    val refreshPlainLyricsForVerbatim: Boolean get() = false
    suspend fun lyrics(track: SourceTrackDetails): SourceLyrics?
    fun timing(track: SourceTrackDetails): SourceLyricTiming
}
interface SourceTrackTheme { suspend fun colorArgb(track: SourceTrackDetails): Long? }
enum class SourcePlaybackPurpose { PLAYBACK, PREFETCH, CACHE }
data class SourcePlaybackRequest(val track: SourceTrackDetails, val qualityId: String? = null,
    val supportedMimeTypes: Set<String> = emptySet(), val deviceCapabilities: Set<String> = emptySet(),
    val automatic: Boolean = false, val purpose: SourcePlaybackPurpose = SourcePlaybackPurpose.PLAYBACK)
data class SourceMediaSegment(val url: String, val headers: Map<String, String> = emptyMap(),
    val byteRange: String? = null, val alternatives: List<String> = emptyList())
data class SourcePlaybackResource(
    val address: PlaybackAddress, val qualityId: String, val format: String,
    val inlineManifest: String? = null, val segments: List<SourceMediaSegment> = emptyList(),
    val recovery: SourcePayload = SourcePayload(), val accountId: String? = null,
    val bitRate: Int = 0, val actualFormat: String? = null, val trial: Boolean = false,
    val verificationPending: Boolean = false
)
interface SourceMediaPlayback {
    val defaultQualityId: String get() = "LOSSLESS"
    val followsMainQualityPreference: Boolean get() = false
    fun normalizePreferredQuality(savedQualityId: String?, mainQualityId: String?): String = savedQualityId ?: defaultQualityId
    fun fallbackQualityIds(preferredId: String): List<String> {
        val order = listOf("STANDARD", "EXHIGH", "LOSSLESS", "HI_RES", "DOLBY")
        val index = order.indexOf(preferredId).takeIf { it >= 0 } ?: 0
        return order.take(index + 1).asReversed()
    }
    suspend fun enrich(track: SourceTrackDetails): SourceTrackDetails = track
    suspend fun qualities(track: SourceTrackDetails): List<SourceAudioQuality>
    /** 逐档探测的包可在每确认一档后回报当前已确认的完整列表；默认查完后一次性回报。 */
    suspend fun qualities(track: SourceTrackDetails, onProgress: (List<SourceAudioQuality>) -> Unit): List<SourceAudioQuality> =
        qualities(track).also(onProgress)
    suspend fun resolve(request: SourcePlaybackRequest): SourcePlaybackResource
    suspend fun recover(request: SourcePlaybackRequest, previous: SourcePlaybackResource): SourcePlaybackResource
    suspend fun availability(tracks: List<SourceTrackDetails>): List<TrackAccessInfo>
}
