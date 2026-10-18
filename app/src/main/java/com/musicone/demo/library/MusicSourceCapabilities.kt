package com.musicone.demo

/** 浏览入口与歌曲来源分开，补充音源不会自动变成首页或登录入口。 */
internal val MAIN_MUSIC_SOURCES = listOf(MusicSource.QQ, MusicSource.KUGOU, MusicSource.NETEASE, MusicSource.LOCAL)
internal val MusicSource.requiresAccount get() = this != MusicSource.LOCAL
internal val MusicSource.supportsLibraryEditing get() = this == MusicSource.LOCAL || this == MusicSource.BILIBILI
internal const val UNIFIED_PLAYLIST_ID = "library-unified"
internal const val ALL_LOCAL_PLAYLIST_ID = "library-all-local"
internal const val PHONE_STORAGE_PLAYLIST_ID = "library-phone-storage"
internal val MusicPlaylist.isLocalLibraryMirror get() = id == ALL_LOCAL_PLAYLIST_ID || id == PHONE_STORAGE_PLAYLIST_ID
internal val MusicPlaylist.isManagedLibrary get() = id.startsWith("library-")

internal data class LibraryFolder(val id: String, val uri: String, val title: String, val copyFiles: Boolean)
internal data class LibrarySubscription(val playlistId: String, val folderId: Long, val ownerId: String, val manualOrder: Boolean)
internal data class LibrarySnapshot(
    val tracks: List<MusicTrack> = emptyList(),
    val playlists: List<MusicPlaylist> = emptyList(),
    val folders: List<LibraryFolder> = emptyList(),
    val subscriptions: List<LibrarySubscription> = emptyList(),
    val lyricRevision: Long = 0L,
) {
    val unified get() = playlists.firstOrNull { it.id == UNIFIED_PLAYLIST_ID } ?: libraryPlaylist(UNIFIED_PLAYLIST_ID, "综合歌单", emptyList())
    val allLocal get() = libraryPlaylist(ALL_LOCAL_PLAYLIST_ID, "全部本地音乐", tracks.filter { it.source == MusicSource.LOCAL })
    val localPlaylists get() = playlists.filter { it.id != UNIFIED_PLAYLIST_ID }
}

internal fun libraryPlaylist(id: String, title: String, tracks: List<MusicTrack>, description: String = "") = MusicPlaylist(
    id, MusicSource.LOCAL, title, "${tracks.size} 首歌曲", description, tracks.size,
    0xFFB5A1D4, 0xFF675785, title.take(1), tracks, tracks.firstOrNull()?.artworkUrl, isOwned = true,
)
