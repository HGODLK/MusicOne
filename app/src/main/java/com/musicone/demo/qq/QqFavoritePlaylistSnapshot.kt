package com.musicone.demo

internal class QqFavoritePlaylistSnapshot {
    private var cloudPlaylist: MusicPlaylist? = null

    fun clear() { cloudPlaylist = null }

    fun record(playlist: MusicPlaylist) { cloudPlaylist = playlist }

    // 始终从云端原始快照叠加当前操作，撤销旧覆盖时也能恢复准确的歌曲和数量。
    fun present(changes: Map<String, Pair<MusicTrack, Boolean>>): MusicPlaylist? =
        cloudPlaylist?.withFavoriteChanges(changes)
}
