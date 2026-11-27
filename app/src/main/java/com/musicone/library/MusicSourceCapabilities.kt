package com.musicone

/** 只列出主音源和本地音乐，补充音源不会出现在主音源切换列表里。 */
internal val MAIN_MUSIC_SOURCES get() = com.musicone.sourceintegration.SourcePackageController.current()
    ?.available(com.musicone.source.sdk.SourceKind.MAIN).orEmpty().map { MusicSource.fromId(it.entry.descriptor.id) } + MusicSource.LOCAL
/** 区分“支持登录”和“必须登录”，支持游客模式的音源依然允许用户点登录。 */
internal val MusicSource.supportsAuthentication get() = com.musicone.sourceintegration.SourcePackageController.current()?.running(id)?.entry?.authentication != null
internal val MusicSource.requiresAccount get() = com.musicone.sourceintegration.SourcePackageController.current()?.running(id)?.entry?.descriptor?.requiresLogin == true
/** 已启用且既不提供登录也不强制登录的音源，界面显示“无需登录”而不是登录提示。 */
internal val MusicSource.loginFree get() = this != MusicSource.LOCAL &&
    com.musicone.sourceintegration.SourcePackageController.current()?.running(id) != null && !supportsAuthentication && !requiresAccount
/** 与能力校验一致，只看包是否真正提供设置页。 */
internal val MusicSource.supportsSourceSettings get() =
    com.musicone.sourceintegration.SourcePackageController.current()?.running(id)?.entry?.settings != null
internal val MusicSource.supportsLibraryEditing get() = this == MusicSource.LOCAL ||
    com.musicone.sourceintegration.SourcePackageController.current()?.running(id)?.entry?.descriptor?.permissions?.editTrackMetadata == true
/** 本地和允许编辑资料的源默认走综合歌单；其余音源须声明收藏服务，且若提供资料库分区则包含“我喜欢”。 */
internal val MusicSource.supportsRemoteFavorites get(): Boolean {
    if (supportsLibraryEditing) return false
    val entry = com.musicone.sourceintegration.SourcePackageController.current()?.running(id)?.entry ?: return false
    if (entry.favorites == null) return false
    val collections = entry.collections
    return collections == null || com.musicone.source.sdk.SourceLibrarySection.FAVORITE_TRACKS in collections.librarySections
}
internal val MusicSource.supportsManualLyrics get() = this == MusicSource.LOCAL ||
    com.musicone.sourceintegration.SourcePackageController.current()?.running(id)?.entry?.descriptor?.permissions?.replaceManualLyrics == true
internal val MusicSource.supportsLyricOffset get() = this == MusicSource.LOCAL ||
    com.musicone.sourceintegration.SourcePackageController.current()?.running(id)?.entry?.descriptor?.permissions?.adjustLineOffset == true
internal val MusicSource.supportsOnlineLyrics get() =
    com.musicone.sourceintegration.SourcePackageController.current()?.running(id)?.entry?.lyricProvider != null
internal const val UNIFIED_PLAYLIST_ID = "library-unified"
internal const val ALL_LOCAL_PLAYLIST_ID = "library-all-local"
internal const val PHONE_STORAGE_PLAYLIST_ID = "library-phone-storage"
internal val MusicPlaylist.isLocalLibraryMirror get() = id == ALL_LOCAL_PLAYLIST_ID || id == PHONE_STORAGE_PLAYLIST_ID
internal val MusicPlaylist.isManagedLibrary get() = id.startsWith("library-")

internal data class LibraryFolder(val id: String, val uri: String, val title: String, val copyFiles: Boolean)
internal data class LibrarySubscription(val playlistId: String, val folderId: Long, val ownerId: String, val manualOrder: Boolean,
    val sourceId: String = "com.musicone.source.bilibili", val accountId: String = ownerId,
    val remoteCollectionId: String = folderId.toString(), val revision: Long = 0)
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
