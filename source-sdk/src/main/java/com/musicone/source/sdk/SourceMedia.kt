package com.musicone.source.sdk

data class SourcePayload(val format: String = "", val version: Int = 1, val encoded: String = "")
data class SourceEntityRef(val id: String, val name: String, val kind: String, val payload: SourcePayload = SourcePayload())
enum class TrackAccessBadge { MEMBERSHIP, PURCHASE }
class TrackAccessInfo(
    val badge: TrackAccessBadge? = null, val trialAvailable: Boolean = false,
    val playable: Boolean = true, val reason: String? = null
) {
    var displayBadge: SourceTrackBadge? = null
        private set

    // 保留 API 1 原有的四参数构造和默认参数，兼容旧调用方。
    constructor(badge: TrackAccessBadge? = null, trialAvailable: Boolean = false,
        playable: Boolean = true, reason: String? = null, displayBadge: SourceTrackBadge?) :
        this(badge, trialAvailable, playable, reason) {
        this.displayBadge = displayBadge
    }

    // 保持原 data class 的解构和 copy 签名兼容，同时透传新增字段。
    operator fun component1(): TrackAccessBadge? = badge
    operator fun component2(): Boolean = trialAvailable
    operator fun component3(): Boolean = playable
    operator fun component4(): String? = reason
    fun copy(badge: TrackAccessBadge? = this.badge, trialAvailable: Boolean = this.trialAvailable,
        playable: Boolean = this.playable, reason: String? = this.reason): TrackAccessInfo =
        TrackAccessInfo(badge, trialAvailable, playable, reason, displayBadge)

    override fun equals(other: Any?): Boolean = other is TrackAccessInfo && badge == other.badge &&
        trialAvailable == other.trialAvailable && playable == other.playable &&
        reason == other.reason && displayBadge == other.displayBadge
    override fun hashCode(): Int {
        var result = badge?.hashCode() ?: 0
        result = 31 * result + trialAvailable.hashCode()
        result = 31 * result + playable.hashCode()
        result = 31 * result + (reason?.hashCode() ?: 0)
        return 31 * result + (displayBadge?.hashCode() ?: 0)
    }
    override fun toString(): String = "TrackAccessInfo(badge=$badge, trialAvailable=$trialAvailable, playable=$playable, reason=$reason, displayBadge=$displayBadge)"
}
data class SourceAudioQuality(val id: String, val label: String, val mimeType: String? = null,
    val available: Boolean = true, val reason: String? = null, val payload: SourcePayload = SourcePayload())
data class SourceArtwork(val url: String? = null, val startArgb: Long = 0, val endArgb: Long = 0, val mark: String = "")

// stableId 沿用已有本地曲库 ID，不要按包版本或 remoteId 重新生成。
data class SourceTrackDetails(
    val track: SourceTrack,
    val stableId: String,
    val album: String = "",
    val durationMs: Long = 0,
    val artwork: SourceArtwork = SourceArtwork(url = track.artworkUrl),
    val artists: List<SourceEntityRef> = emptyList(),
    val albumRef: SourceEntityRef? = null,
    val access: TrackAccessInfo = TrackAccessInfo(),
    val qualities: List<SourceAudioQuality> = emptyList(),
    val lyrics: SourceLyrics? = null,
    val localUri: String = "",
    val payload: SourcePayload = SourcePayload(),
    val artistsText: String = track.artists.joinToString(" / "),
    val previewUrl: String = "",
    val identityAliases: List<String> = emptyList()
)
data class SourceCollection(
    val identity: TrackIdentity, val stableId: String, val title: String,
    val subtitle: String = "", val description: String = "", val count: Int? = null,
    val artwork: SourceArtwork = SourceArtwork(), val owned: Boolean = false,
    val accountFavorites: Boolean = false, val payload: SourcePayload = SourcePayload()
)
data class SourceEntity(
    val reference: SourceEntityRef, val description: String = "", val artwork: SourceArtwork = SourceArtwork(),
    val attributes: Map<String, String> = emptyMap()
)
enum class SourceFetchPolicy { CACHE_FIRST, REFRESH }

// nextCursor 为 null 表示没有下一页，失败抛异常；取消时照常抛出，不要返回空页。
data class SourceCursorRequest(val cursor: String? = null, val limit: Int = 50, val policy: SourceFetchPolicy = SourceFetchPolicy.CACHE_FIRST)
class SourceOperationException(val code: String, message: String, val retryable: Boolean = false) : Exception(message)
