package com.musicone.source.kugou

internal enum class MusicSource(val label: String) { KUGOU("酷狗音乐") }
internal enum class MusicAccessBadge { VIP, PAID }
internal data class MusicTrackAccess(val badge: MusicAccessBadge?, val trialAvailable: Boolean, val playable: Boolean)
internal data class MusicTrack(
    val id: String, val source: MusicSource = MusicSource.KUGOU, val title: String, val artists: String,
    val album: String = "", val durationMs: Long = 0, val artworkStart: Long = 0, val artworkEnd: Long = 0,
    val artworkMark: String = "", val previewUrl: String = "", val artworkUrl: String? = null,
    val catalogId: String = "", val mediaId: String = "", val qualityIds: Map<AudioQuality, String> = emptyMap(),
    val accessBadge: MusicAccessBadge? = null, val trialAvailable: Boolean = false, val playable: Boolean = true,
    val unavailableReason: String? = null, val lyrics: List<TimedLyric> = emptyList(),
    val artistReferences: List<com.musicone.source.sdk.SourceEntityRef> = emptyList(),
    val albumReference: com.musicone.source.sdk.SourceEntityRef? = null,
    val kugouAccess: KugouTrackAccessInfo? = null,
)
internal data class MusicPlaylist(
    val id: String, val source: MusicSource = MusicSource.KUGOU, val title: String, val subtitle: String = "",
    val description: String = "", val count: Int = 0, val artworkStart: Long = 0, val artworkEnd: Long = 0,
    val artworkMark: String = "", val tracks: List<MusicTrack> = emptyList(), val artworkUrl: String? = null,
    val isOwned: Boolean = false, val isPlatformFavorites: Boolean = false
)
internal data class MusicAccount(val source: MusicSource, val userId: String, val nickname: String,
    val avatarUrl: String?, val hasVipAccess: Boolean = false, val backgroundUrl: String? = null,
    val signature: String = "", val follows: Int = 0, val followers: Int = 0)
internal enum class PlatformQrLoginStatus { IDLE, LOADING, WAITING_SCAN, WAITING_CONFIRM, EXPIRED, SUCCESS, FAILED }
internal enum class AudioQuality(val label: String, val bitRate: Int) {
    STANDARD("标准", 128000), HIGHER("较高", 192000), EXHIGH("极高", 320000), LOSSLESS("无损", 999000), HI_RES("Hi-Res", 1999000), DOLBY("Dolby", 768000)
}
internal data class PlaybackSource(val url: String, val requestedQuality: AudioQuality, val actualQuality: AudioQuality,
    val bitRate: Int, val format: String, val trial: Boolean, val actualFormat: String? = null,
    val verificationPending: Boolean = false, val requestHeaders: Map<String, String> = emptyMap(),
    val cacheKey: String? = null, val mimeType: String? = null)
internal data class LyricWord(val startMs: Long, val endMs: Long, val text: String)
internal data class TimedLyric(val timeMs: Long, val text: String, val translation: String? = null,
    val words: List<LyricWord> = emptyList(), val parserVersion: Int = 0, val transliteration: String? = null)
internal fun MusicTrack.remoteId(): String = id.removePrefix("kugou-")
internal fun MusicPlaylist.remoteId(): String = id.removePrefix("kugou-")
internal fun MusicPlaylist.isAccountFavorites(): Boolean = isOwned && isPlatformFavorites
internal fun AudioQuality.displayLabel(source: MusicSource): String = label
internal class TrackUnavailableException(message: String) : PlatformApiException(message)
internal fun Throwable.stopsPlaybackFallback(): Boolean = this is PlatformSecurityVerificationRequired || this is KugouPlaybackBlocked ||
    (this is PlatformApiException && apiCode in setOf(301, 401, 403, 429))
