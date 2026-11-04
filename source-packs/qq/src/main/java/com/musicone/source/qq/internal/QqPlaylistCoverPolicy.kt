package com.musicone.source.qq.internal

/** 自建和我喜欢采用原始加入顺序首曲；推荐、收藏歌单及专辑保留独立封面。 */
internal fun qqPlaylistCoverSource(playlist: MusicPlaylist): MusicPlaylist =
    if (!playlist.isQqSearchAlbum && (playlist.isOwned || playlist.providerData["qqCoverFromLatest"] == "true" || playlist.id == QQ_FAVORITES_PLAYLIST_ID ||
            playlist.id.startsWith(QQ_PROFILE_DIRECTORY_ID_PREFIX) || playlist.qqDirectoryId != null))
        playlist.copy(artworkUrl = null)
    else playlist
