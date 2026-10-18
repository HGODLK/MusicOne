package com.musicone.demo

/** 订阅歌单由远端收藏夹维护，普通添加和分 P 导入都只能选择可手动管理的目标。 */
internal fun LibrarySnapshot.manualAdditionTargets(): List<MusicPlaylist> {
    val subscribed = subscriptions.mapTo(hashSetOf()) { it.playlistId }
    return playlists.filterNot { it.isLocalLibraryMirror || it.id in subscribed }
}
