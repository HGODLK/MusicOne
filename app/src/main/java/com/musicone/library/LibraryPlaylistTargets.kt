package com.musicone

/** 过滤掉远端订阅歌单，只返回允许用户手动加歌的本地歌单。 */
internal fun LibrarySnapshot.manualAdditionTargets(): List<MusicPlaylist> {
    val subscribed = subscriptions.mapTo(hashSetOf()) { it.playlistId }
    return playlists.filterNot { it.isLocalLibraryMirror || it.id in subscribed }
}
