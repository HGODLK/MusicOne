package com.musicone.source.qq.internal

/** 自建歌单和“我喜欢”用第一首歌当封面，推荐/收藏歌单和专辑保留自带封面。 */
internal fun qqPlaylistCoverSource(playlist: MusicPlaylist): MusicPlaylist =
    if (!playlist.isQqSearchAlbum && (playlist.isOwned || playlist.providerData["qqCoverFromLatest"] == "true" || playlist.id == QQ_FAVORITES_PLAYLIST_ID ||
            playlist.id.startsWith(QQ_PROFILE_DIRECTORY_ID_PREFIX) || playlist.qqDirectoryId != null))
        playlist.copy(artworkUrl = null)
    else playlist
