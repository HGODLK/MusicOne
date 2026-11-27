package com.musicone

/** 离线查看“我喜欢”时直接用本地已有快照，不拿占位 ID 去发网络请求。 */
internal fun mainSourceOfflineFavorites(playlist: MusicPlaylist): MusicPlaylist = playlist.copy(
    isPlatformFavorites = true,
    providerData = playlist.providerData + ("templateEphemeral" to "true"),
)
