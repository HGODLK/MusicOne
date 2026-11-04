package com.musicone.source.qq.internal

/** 既有QQ协议的包私有资料模型，SDK出口由桥接层转换。 */
internal enum class MusicSource(val label: String, val accent: Long) {
    QQ("QQ 音乐", 0xFF31C27C), NETEASE("网易云音乐", 0xFFEC4141), KUGOU("酷狗音乐", 0xFF009AF3),
    LOCAL("本地音乐", 0xFF9B8AC4), BILIBILI("哔哩哔哩", 0xFFFB7299);
    val id get() = "com.musicone.source.${name.lowercase()}"
    val idPrefix get() = "${name.lowercase()}-"
    companion object { fun fromId(id: String): MusicSource? = entries.firstOrNull { it.id == id || it.name == id } }
}
internal enum class MusicAccessBadge { VIP, PAID }
internal data class MusicTrackAccess(val badge: MusicAccessBadge?, val trialAvailable: Boolean, val playable: Boolean)
internal data class MusicArtist(val id: String, val name: String)
internal data class MusicTrack(
    val id: String, val source: MusicSource, val title: String, val artists: String, val album: String,
    val durationMs: Long, val artworkStart: Long, val artworkEnd: Long, val artworkMark: String, val previewUrl: String,
    val artworkUrl: String? = null, val catalogId: String = "", val mediaId: String = "", val providerType: Int = 0,
    val qualityIds: Map<AudioQuality, String> = emptyMap(), val accessBadge: MusicAccessBadge? = null,
    val trialAvailable: Boolean = false, val playable: Boolean = true, val unavailableReason: String? = null,
    val lyrics: List<TimedLyric> = emptyList(), val artistRefs: List<MusicArtist> = emptyList(), val albumId: String = "",
    val localUri: String = "", val providerData: Map<String, String> = emptyMap(),
    val sourceAccountId: String? = null, val remoteKey: String = "",
)
internal data class MusicPlaylist(
    val id: String, val source: MusicSource, val title: String, val subtitle: String, val description: String,
    val count: Int, val artworkStart: Long, val artworkEnd: Long, val artworkMark: String, val tracks: List<MusicTrack>,
    val artworkUrl: String? = null, val isOwned: Boolean = false, val isPlatformFavorites: Boolean = false,
    val providerData: Map<String, String> = emptyMap(),
)
internal fun MusicTrack.remoteId(): String = id.removePrefix("${source.name.lowercase()}-")
internal fun MusicPlaylist.remoteId(): String = id.removePrefix("${source.name.lowercase()}-")
internal data class MusicAccount(
    val source: MusicSource, val userId: String, val nickname: String, val avatarUrl: String?, val hasVipAccess: Boolean = false,
    val backgroundUrl: String? = null, val signature: String = "", val follows: Int = 0, val followers: Int = 0,
)
internal data class PlatformSession(val source: MusicSource, val credential: String, val deviceId: String, val account: MusicAccount?) {
    override fun toString(): String = "PlatformSession(source=$source, credentials=<已隐藏>)"
}
internal enum class PlatformLoginMethod { QR_CODE, WECHAT_QR_CODE, SMS }
internal enum class PlatformQrLoginStatus { IDLE, LOADING, WAITING_SCAN, WAITING_CONFIRM, EXPIRED, SUCCESS, FAILED }
internal enum class ArtistSongSort(val order: Int, val label: String) { Popular(1, "热门歌曲"), Latest(0, "最新歌曲") }
