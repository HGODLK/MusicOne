package com.musicone

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun playlistTrackIdentities(track: MusicTrack): Set<String> = buildSet {
    add(track.id)
    addAll(track.identityAliases)
}

internal fun existingPlaylistSelections(playlist: MusicPlaylist, selected: List<MusicTrack>): Set<String> {
    val wanted = mutableMapOf<String, MutableSet<String>>()
    selected.forEach { song -> playlistTrackIdentities(song).forEach { identity ->
        wanted.getOrPut(identity) { mutableSetOf() }.add(song.id)
    } }
    val found = mutableSetOf<String>()
    for (track in playlist.tracks) {
        playlistTrackIdentities(track).forEach { wanted[it]?.let(found::addAll) }
        if (found.size == selected.size) break
    }
    return found
}

internal fun playlistAdditionCandidates(playlist: MusicPlaylist, selected: List<MusicTrack>): List<MusicTrack> =
    if (playlist.isManagedLibrary) selected else selected.filter { it.source == playlist.source }

/** 成员分页经过音源 SDK，找到全部所选曲目即可停止。 */
internal class PlaylistMembershipRepository(private val preferences: PlatformPreferences) {
    private val catalog = PlatformCatalogRepository(preferences.applicationContext)
    suspend fun members(playlist: MusicPlaylist, songs: List<MusicTrack>): Set<String> {
        if (playlist.isManagedLibrary) return existingPlaylistSelections(playlist, songs)
        val owner = preferences.readSession(playlist.source).account?.userId
        val result = mutableSetOf<String>()
        val seen = mutableSetOf<List<String>>()
        var cursor: PlaylistTrackCursor? = null
        do {
            currentCoroutineContext().ensureActive()
            val page = catalog.playlistPage(playlist, cursor)
            val signature = page.playlist.tracks.map(MusicTrack::id)
            check(signature.isEmpty() || seen.add(signature)) { "歌单成员分页重复，请重试" }
            result += existingPlaylistSelections(page.playlist, songs)
            cursor = page.next
        } while (cursor != null && result.size < songs.size)
        currentCoroutineContext().ensureActive()
        if (preferences.readSession(playlist.source).account?.userId != owner) {
            throw kotlinx.coroutines.CancellationException("歌单账号已切换")
        }
        return result
    }
}
