package com.musicone

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 勾选只来自已有成员或成功写入；查询失败、部分成功不能把整批标为已添加。
 * 先用本地结果立即显示（[cached]），再联网核对（[read]）后替换；只有没有本地结果时才显示加载中。
 */
internal class PlaylistAddMembership(
    private val cached: suspend (MusicPlaylist, List<MusicTrack>) -> Set<String>? = { _, _ -> null },
    private val read: suspend (MusicPlaylist, List<MusicTrack>) -> Set<String>,
) {
    var selected by mutableStateOf(emptyList<MusicTrack>()); private set
    var generation by mutableIntStateOf(0); private set
    var checking by mutableStateOf(emptySet<String>()); private set
    private var existing by mutableStateOf(emptyMap<String, Set<String>>())
    private var confirmed by mutableStateOf(emptyMap<String, Set<String>>())
    private var removed by mutableStateOf(emptyMap<String, Set<String>>())
    private val limit = Semaphore(4)
    // 本轮已开始核对的歌单，行重组时不重复联网。
    private val started = mutableSetOf<String>()

    fun reset(songs: List<MusicTrack>) {
        generation++
        selected = songs.distinctBy(MusicTrack::id)
        existing = emptyMap()
        confirmed = emptyMap()
        removed = emptyMap()
        checking = emptySet()
        started.clear()
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
        if (target.isManagedLibrary || target.id in started) return
        val token = generation
        val songs = playlistAdditionCandidates(target, selected)
        if (songs.isEmpty()) return
        started += target.id
        try {
            val local = try { withContext(Dispatchers.IO) { cached(target, songs) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (token != generation) return
            if (local != null) existing = existing + (target.id to local)
            else checking = checking + target.id
            val result = limit.withPermit { withContext(Dispatchers.IO) { read(target, songs) } }
            if (token == generation) existing = existing + (target.id to result)
        } catch (cancelled: CancellationException) {
            // 行被移出组合时取消，下次显示重新核对。
            if (token == generation) started -= target.id
            throw cancelled
        } catch (_: Exception) {
            /* 核对失败保留本地结果；没有本地结果时不显示成功勾，写入失败仍由操作菜单反馈。 */
        } finally { if (token == generation) checking = checking - target.id }
    }
}
