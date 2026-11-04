package com.musicone.source.netease

internal enum class NeteaseAccessBadge(val label: String) {
    VIP("vip"),
    PAID("付费"),
}

internal data class NeteaseArtist(val id: String, val name: String)

internal data class NeteaseTrack(
    val id: String,
    val source: NeteaseOrigin,
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
    val qualityIds: Map<NeteaseQuality, String> = emptyMap(),
    val accessBadge: NeteaseAccessBadge? = null,
    val trialAvailable: Boolean = false,
    val playable: Boolean = true,
    val unavailableReason: String? = null,
    val lyrics: List<NeteaseLyricLine> = emptyList(),
    val artistRefs: List<NeteaseArtist> = emptyList(),
    val albumId: String = "",
    val localUri: String = "",
    val providerData: Map<String, String> = emptyMap(),
    val sourceAccountId: String? = null,
    val remoteKey: String = "",
)

internal fun NeteaseTrack.remoteId(): String = remoteKey.ifBlank { id.removePrefix(source.idPrefix) }

internal fun NeteasePlaylist.remoteId(): String = remoteKey.ifBlank { id.removePrefix(source.idPrefix) }

internal data class NeteasePlaylist(
    val id: String,
    val source: NeteaseOrigin,
    val title: String,
    val subtitle: String,
    val description: String,
    val count: Int,
    val artworkStart: Long,
    val artworkEnd: Long,
    val artworkMark: String,
    val tracks: List<NeteaseTrack>,
    val artworkUrl: String? = null,
    val isOwned: Boolean = false,
    val isPlatformFavorites: Boolean = false,
    val providerData: Map<String, String> = emptyMap(),
    val sourceAccountId: String? = null,
    val remoteKey: String = "",
)
