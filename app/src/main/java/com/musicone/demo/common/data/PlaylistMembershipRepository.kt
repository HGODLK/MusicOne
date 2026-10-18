package com.musicone.demo

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun playlistTrackIdentities(track: MusicTrack): Set<String> = buildSet {
    add(track.id)
    if (track.source == MusicSource.QQ) {
        track.catalogId.toLongOrNull()?.takeIf { it > 0 }?.let { add("qq-$it") }
        track.songMid.takeIf(String::isNotBlank)?.let { add("qq-$it") }
    }
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

/** 只读取成员身份；QQ 不补歌曲权限、封面或播放地址，找到所选歌曲即可结束分页。 */
internal class PlaylistMembershipRepository(private val preferences: PlatformPreferences) {
    private val qq = QqPlaylistPaging()
    private val qqLibrary = QqLibraryClient()
    private val kugou = KugouApiClient()
    suspend fun members(playlist: MusicPlaylist, songs: List<MusicTrack>): Set<String> {
        val session = preferences.readSession(playlist.source)
        val vip = session.account?.hasVipAccess == true
        if (playlist.source == MusicSource.NETEASE) {
            val ids = NeteaseApiClient().playlistTrackIds(playlist.remoteId(), session.credential)
            if (preferences.readSession(playlist.source).credential != session.credential) {
                throw kotlinx.coroutines.CancellationException("歌单账号已切换")
            }
            return songs.filter { it.id in ids }.mapTo(hashSetOf(), MusicTrack::id)
        }
        val result = mutableSetOf<String>()
        val seen = mutableSetOf<List<String>>()
        var cursor: PlaylistTrackCursor? = null
        do {
            currentCoroutineContext().ensureActive()
            val page = when (playlist.source) {
                MusicSource.QQ -> if (playlist.id.startsWith(QQ_PROFILE_DIRECTORY_ID_PREFIX)) {
                    qqLibrary.profileDirectoryPage(playlist, session.credential, vip, cursor?.offset ?: 0,
                        resolveAccess = false)
                } else qq.page(playlist, session.credential, vip, cursor?.offset ?: 0,
                    resolveAccess = false)
                MusicSource.KUGOU -> kugou.playlistPage(playlist, session.credential, cursor)
                else -> PlaylistTrackPage(playlist)
            }
            val signature = page.playlist.tracks.map(MusicTrack::id)
            check(signature.isEmpty() || seen.add(signature)) { "歌单成员分页重复，请重试" }
            result += existingPlaylistSelections(page.playlist, songs)
            cursor = page.next
        } while (cursor != null && result.size < songs.size)
        currentCoroutineContext().ensureActive()
        if (preferences.readSession(playlist.source).credential != session.credential) {
            throw kotlinx.coroutines.CancellationException("歌单账号已切换")
        }
        return result
    }
}
