package com.musicone

private val LEGACY_ALBUM_ARTWORK_URL = Regex(
    "^https?://(?:y\\.gtimg\\.cn|y\\.qq\\.com)/music/photo_new/T002R\\d+x\\d+M000([A-Za-z0-9]+)\\.jpg(?:\\?.*)?$",
    RegexOption.IGNORE_CASE,
)

/** 兼容旧版专辑封面地址，下载失败时尝试同一专辑的其他等价 URL。 */
internal fun artworkDownloadCandidates(imageUrl: String): List<String> {
    val legacyAlbumId = LEGACY_ALBUM_ARTWORK_URL.matchEntire(imageUrl)?.groupValues?.getOrNull(1)
        ?: return listOf(imageUrl)
    return listOf(
        imageUrl,
        "https://y.qq.com/music/photo_new/T002R500x500M000$legacyAlbumId.jpg",
        "https://y.gtimg.cn/music/photo_new/T002R300x300M000$legacyAlbumId.jpg",
        "https://y.qq.com/music/photo_new/T002R300x300M000$legacyAlbumId.jpg",
    ).distinct()
}
