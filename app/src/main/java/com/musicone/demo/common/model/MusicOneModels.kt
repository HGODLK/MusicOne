package com.musicone.demo

enum class MusicSource(
    val label: String,
    val accent: Long,
) {
    NETEASE("网易云音乐", 0xFFEC4141),
    QQ("QQ 音乐", 0xFF31C27C),
    KUGOU("酷狗音乐", 0xFF009AF3),
    LOCAL("本地音乐", 0xFF9B8AC4),
    BILIBILI("哔哩哔哩", 0xFFFB7299),
}

enum class MusicAccessBadge(val label: String) {
    VIP("vip"),
    PAID("付费"),
}

data class MusicArtist(val mid: String, val name: String)

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
    val songMid: String = "",
    val artistRefs: List<MusicArtist> = emptyList(),
    val albumMid: String = "",
    val qqAccess: QqTrackAccessInfo? = null,
    val localUri: String = "",
    val bilibiliBvid: String = "",
    val bilibiliCid: Long = 0L,
)

internal fun MusicTrack.remoteId(): String = id.removePrefix(source.idPrefix)

internal fun MusicPlaylist.remoteId(): String = id.removePrefix(source.idPrefix)

private val MusicSource.idPrefix: String
    get() = when (this) {
        MusicSource.NETEASE -> "netease-"
        MusicSource.QQ -> "qq-"
        MusicSource.KUGOU -> "kugou-"
        MusicSource.LOCAL -> "local-"
        MusicSource.BILIBILI -> "bilibili-"
    }

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
    val qqDirectoryId: Long? = null,
    val isOwned: Boolean = false,
    val isPlatformFavorites: Boolean = false,
)
