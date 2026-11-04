package com.musicone

enum class MusicAccessBadge(val label: String) {
    VIP("vip"),
    PAID("付费"),
}

data class MusicArtist(val id: String, val name: String,
    val reference: com.musicone.source.sdk.SourceEntityRef? = null)

data class MusicTrack(
    val id: String,
    val source: MusicSource,
    val title: String,
    val artists: String,
    val album: String,
    val durationMs: Long,
    val artworkStart: Long,
    val artworkEnd: Long,
    val artworkMark: String,
    val previewUrl: String,
    val artworkUrl: String? = null,
    val catalogId: String = "",
    val mediaId: String = "",
    val providerType: Int = 0,
    val qualityIds: Map<AudioQuality, String> = emptyMap(),
    val accessBadge: MusicAccessBadge? = null,
    val trialAvailable: Boolean = false,
    val playable: Boolean = true,
    val unavailableReason: String? = null,
    val lyrics: List<TimedLyric> = emptyList(),
    val artistRefs: List<MusicArtist> = emptyList(),
    val albumId: String = "",
    val localUri: String = "",
    val providerData: Map<String, String> = emptyMap(),
    val identityAliases: List<String> = emptyList(),
    val sourceAccountId: String? = null,
    val remoteKey: String = "",
    val displayBadge: com.musicone.source.sdk.SourceTrackBadge? = null,
    val albumReference: com.musicone.source.sdk.SourceEntityRef? = null,
    // null 表示旧记录尚未保存公开音质资料；空列表则是音源明确返回的结果。
    val audioQualities: List<com.musicone.source.sdk.SourceAudioQuality>? = null,
)

internal fun MusicTrack.remoteId(): String = remoteKey.ifBlank { id.removePrefix(source.idPrefix) }

internal fun MusicPlaylist.remoteId(): String = remoteKey.ifBlank { id.removePrefix(source.idPrefix) }

data class MusicPlaylist(
    val id: String,
    val source: MusicSource,
    val title: String,
    val subtitle: String,
    val description: String,
    val count: Int,
    val artworkStart: Long,
    val artworkEnd: Long,
    val artworkMark: String,
    val tracks: List<MusicTrack>,
    val artworkUrl: String? = null,
    val isOwned: Boolean = false,
    val isPlatformFavorites: Boolean = false,
    val providerData: Map<String, String> = emptyMap(),
    val sourceAccountId: String? = null,
    val remoteKey: String = "",
)
