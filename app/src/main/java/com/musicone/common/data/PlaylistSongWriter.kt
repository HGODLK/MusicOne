package com.musicone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** 添加切换和离页移除共用写入器，权限与歌曲身份在真正提交时再次核对。 */
internal class PlaylistSongWriter(private val preferences: PlatformPreferences, private val library: MusicLibraryStore) {
    private val online = PlatformPlaylistSongRepository(preferences.applicationContext)

    suspend fun change(target: MusicPlaylist, song: MusicTrack, add: Boolean,
        session: PlatformSession = preferences.readSession(target.source)) {
        if (target.isManagedLibrary) {
            val current = library.state.value.manualAdditionTargets().firstOrNull { it.id == target.id }
                ?: error("此歌单不支持手动修改")
            if (add) library.addTracks(current.id, listOf(song))
            else current.tracks.filter { playlistTrackIdentities(it).intersect(playlistTrackIdentities(song)).isNotEmpty() }
                .forEach { library.removeTrack(current.id, it.id) }
        } else {
            checkSession(target, session)
            withContext(Dispatchers.IO) {
                online.change(session, online.target(session, target.id), song, add)
            }
            checkSession(target, session)
        }
    }

    suspend fun changeAll(target: MusicPlaylist, songs: List<MusicTrack>, add: Boolean, session: PlatformSession) {
        if (target.isManagedLibrary && add) library.addTracks(target.id, songs)
        else {
            require(!target.isManagedLibrary)
            checkSession(target, session)
            withContext(Dispatchers.IO) { online.changeBatch(session, online.target(session, target.id), songs, add) }
            checkSession(target, session)
        }
    }

    private fun checkSession(target: MusicPlaylist, session: PlatformSession) {
        if (preferences.readSession(target.source).account?.userId != session.account?.userId) throw CancellationException("账号已切换")
    }
}
