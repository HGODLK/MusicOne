package com.musicone

internal const val MUSIC_ONE_GITHUB_PAGE = "https://github.com/HGODLK/MusicOne"
internal const val MUSIC_ONE_UPDATE_GATEWAY = "https://dl.shuyunrainbow.cn"

/** 只给校验过的官方下载地址套加速网关，保留原链接里的中文编码。 */
internal fun updateDownloadGatewayUrl(downloadUrl: String): String {
    require(downloadUrl.startsWith(MUSIC_ONE_DOWNLOAD_ROOT)) { "非官方更新附件" }
    return "$MUSIC_ONE_UPDATE_GATEWAY/$downloadUrl"
}

internal fun updateReleasePageUrl(page: Int): String {
    require(page > 0)
    return "$MUSIC_ONE_UPDATE_GATEWAY/api/update?page=$page"
}
