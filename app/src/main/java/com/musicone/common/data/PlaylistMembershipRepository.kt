package com.musicone

import com.musicone.source.sdk.SourceFetchPolicy
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

/** 查询歌曲是否已在歌单里：优先用音源自带的归属索引，没有就分页拉歌单，找齐所选歌曲就提前停。 */
internal class PlaylistMembershipRepository(private val preferences: PlatformPreferences) {
    private val catalog = PlatformCatalogRepository(preferences.applicationContext)
    private val online = PlatformPlaylistSongRepository(preferences.applicationContext)
    private val detailCache = PlaylistDetailCache(preferences.applicationContext)

    /** 只读本地：音源索引优先，其次是歌单详情缓存；都没有返回 null，调用方显示加载中。 */
    suspend fun cached(playlist: MusicPlaylist, songs: List<MusicTrack>): Set<String>? {
        if (playlist.isManagedLibrary) return existingPlaylistSelections(playlist, songs)
        online.containedTracks(playlist, songs, SourceFetchPolicy.CACHE_FIRST)?.let { return it }
        val owner = preferences.readSession(playlist.source).account?.userId
        return detailCache.read(playlist, owner)?.let { existingPlaylistSelections(it, songs) }
    }

    suspend fun members(playlist: MusicPlaylist, songs: List<MusicTrack>): Set<String> {
        if (playlist.isManagedLibrary) return existingPlaylistSelections(playlist, songs)
        online.containedTracks(playlist, songs, SourceFetchPolicy.REFRESH)?.let { return it }
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
