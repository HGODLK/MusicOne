package com.musicone.demo

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 勾选只来自已有成员或成功写入；查询失败、部分成功不能把整批标为已添加。 */
internal class PlaylistAddMembership(
    private val read: suspend (MusicPlaylist, List<MusicTrack>) -> Set<String>,
) {
    var selected by mutableStateOf(emptyList<MusicTrack>()); private set
    var generation by mutableIntStateOf(0); private set
    var checking by mutableStateOf(emptySet<String>()); private set
    private var existing by mutableStateOf(emptyMap<String, Set<String>>())
    private var confirmed by mutableStateOf(emptyMap<String, Set<String>>())
    private var removed by mutableStateOf(emptyMap<String, Set<String>>())
    private val limit = Semaphore(2)

    fun reset(songs: List<MusicTrack>) {
        generation++
        selected = songs.distinctBy(MusicTrack::id)
        existing = emptyMap()
        confirmed = emptyMap()
        removed = emptyMap()
        checking = emptySet()
    }

    fun members(target: MusicPlaylist): Set<String> =
        applyTo(target.id, if (target.isManagedLibrary) existingPlaylistSelections(target, selected)
        else existing[target.id].orEmpty())

    fun applyTo(id: String, members: Set<String>): Set<String> =
        (members + confirmed[id].orEmpty()) - removed[id].orEmpty()

    fun remaining(target: MusicPlaylist): List<MusicTrack> {
        val members = members(target)
        return playlistAdditionCandidates(target, selected).filterNot { it.id in members }
    }

    fun added(target: MusicPlaylist): Boolean =
        playlistAdditionCandidates(target, selected).isNotEmpty() && remaining(target).isEmpty()

    fun confirm(id: String, songs: List<MusicTrack>, add: Boolean = true) {
        val ids = songs.mapTo(hashSetOf(), MusicTrack::id)
        confirmed = confirmed + (id to if (add) confirmed[id].orEmpty() + ids else confirmed[id].orEmpty() - ids)
        removed = removed + (id to if (add) removed[id].orEmpty() - ids else removed[id].orEmpty() + ids)
    }

    suspend fun check(target: MusicPlaylist) {
        if (target.isManagedLibrary || target.id in existing || target.id in checking) return
        val token = generation
        val songs = playlistAdditionCandidates(target, selected)
        if (songs.isEmpty()) return
        checking = checking + target.id
        try {
            val result = limit.withPermit { withContext(Dispatchers.IO) { read(target, songs) } }
            if (token == generation) existing = existing + (target.id to result)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* 成员确认失败时不显示成功勾，写入失败仍由操作菜单反馈。 */ }
        finally { if (token == generation) checking = checking - target.id }
    }
}
