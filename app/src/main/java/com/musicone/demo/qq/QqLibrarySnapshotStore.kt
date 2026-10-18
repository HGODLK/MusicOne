package com.musicone.demo

import android.content.Context

/** 按账号及分区保存成功加载的目录，单个分区刷新失败不覆盖其他快照。 */
internal class QqLibrarySnapshotStore(
    private val readList: (String) -> List<MusicPlaylist>,
    private val saveList: (String, List<MusicPlaylist>) -> Unit,
) {
    constructor(context: Context) : this(CachedPlaylistStore(context))
    private constructor(store: CachedPlaylistStore) : this(store::read, store::save)

    private fun key(session: PlatformSession, section: String) = "library:${session.cacheNamespace()}:$section"

    fun read(session: PlatformSession): QqLibraryUiState = QqLibraryUiState(
        signedIn = true,
        favoritePlaylist = readList(key(session, "favorites")).firstOrNull(),
        createdPlaylists = readList(key(session, "created")),
        collectedPlaylists = readList(key(session, "collected")),
    )

    fun saveFavorites(session: PlatformSession, playlist: MusicPlaylist) =
        saveList(key(session, "favorites"), listOf(playlist))

    fun saveCreated(session: PlatformSession, playlists: List<MusicPlaylist>) =
        saveList(key(session, "created"), playlists)

    fun saveCollected(session: PlatformSession, playlists: List<MusicPlaylist>) =
        saveList(key(session, "collected"), playlists)
}
