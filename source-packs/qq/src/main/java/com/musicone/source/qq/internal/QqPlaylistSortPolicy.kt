package com.musicone.source.qq.internal

/** 生成歌单排序偏好的存储 key（绑定账号和歌单 ID，改歌单名不会丢失排序）。 */
internal fun qqPlaylistSortKey(playlist: MusicPlaylist, accountId: String?): String? {
    if (playlist.source != MusicSource.QQ || playlist.isQqSearchAlbum) return null
    val owner = accountId?.takeIf { it.isNotBlank() } ?: return null
    val identity = when {
        playlist.id == QQ_FAVORITES_PLAYLIST_ID -> "favorites"
        playlist.id.startsWith(QQ_PROFILE_DIRECTORY_ID_PREFIX) -> playlist.id
        playlist.qqDirectoryId != null -> "$QQ_PROFILE_DIRECTORY_ID_PREFIX${playlist.qqDirectoryId}"
        playlist.id.startsWith("qq-") && playlist.remoteId().toLongOrNull()?.let { it > 0 } == true ->
            "public:${playlist.remoteId().toLong()}"
        else -> return null
    }
    return "${owner.length}:$owner:$identity"
}

internal fun qqPlaylistSortId(saved: String?): String =
    saved?.takeIf { it in listOf("ADDED_DESC", "ADDED_ASC", "NAME") } ?: "ADDED_DESC"
