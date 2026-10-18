package com.musicone.demo

internal const val MUSIC_ONE_GITHUB_PAGE = "https://github.com/HGODLK/MusicOne"
internal const val MUSIC_ONE_UPDATE_GATEWAY = "https://dl.shuyunrainbow.cn"

/** 仅把已校验的官方附件地址交给下载网关，保留中文路径原始编码。 */
internal fun updateDownloadGatewayUrl(downloadUrl: String): String {
    require(downloadUrl.startsWith(MUSIC_ONE_DOWNLOAD_ROOT)) { "非官方更新附件" }
    return "$MUSIC_ONE_UPDATE_GATEWAY/$downloadUrl"
}

internal fun updateReleasePageUrl(page: Int): String {
    require(page > 0)
    return "$MUSIC_ONE_UPDATE_GATEWAY/api/update?page=$page"
}
