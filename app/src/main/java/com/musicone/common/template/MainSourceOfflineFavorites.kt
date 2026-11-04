package com.musicone

/** 离线入口只交付现有快照，不用占位收藏编号发起远端详情和分页请求。 */
internal fun mainSourceOfflineFavorites(playlist: MusicPlaylist): MusicPlaylist = playlist.copy(
    isPlatformFavorites = true,
    providerData = playlist.providerData + ("templateEphemeral" to "true"),
)
