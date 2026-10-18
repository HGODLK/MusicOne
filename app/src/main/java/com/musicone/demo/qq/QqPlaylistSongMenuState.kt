package com.musicone.demo

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class QqPlaylistSongMenuState(
    private val scope: CoroutineScope,
    private val preferences: PlatformPreferences,
    private val repository: QqPlaylistSongRepository = QqPlaylistSongRepository(),
    private val library: MusicLibraryStore? = null,
) {
    private val onlineRepository = PlatformPlaylistSongRepository()
    var removals = PlaylistRemovalActions()
    var writingTarget by mutableStateOf<String?>(null); private set
    val additions = PlaylistAddMembership(PlaylistMembershipRepository(preferences)::members)
    var track by mutableStateOf<MusicTrack?>(null)
        private set
    var anchor by mutableStateOf(Rect.Zero)
        private set
    var fromLyricsTitle by mutableStateOf(false)
        private set
    var expanded by mutableStateOf(false)
        private set
    var choosing by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var targets by mutableStateOf(emptyList<MusicPlaylist>())
        private set
    var surfaceBounds = Rect.Zero
    var surfaceLayer: androidx.compose.ui.graphics.layer.GraphicsLayer? = null
    var backdropLayer: androidx.compose.ui.graphics.layer.GraphicsLayer? = null
    var backdropBounds = Rect.Zero
    var liveBackdrop = false
    var artistArtworkState: MenuArtistArtworkState? = null
    var choosingArtist by mutableStateOf(false)
    var editing by mutableStateOf(false)
        private set
    var editForm by mutableStateOf<LibraryEditForm?>(null)
        private set
    var batchTracks by mutableStateOf(emptyList<MusicTrack>())
        private set

    fun openBatch(songs: List<MusicTrack>, bounds: Rect, chooseTargets: Boolean = true) {
        if (busy || songs.isEmpty()) return
        fromLyricsTitle = false
        track = songs.first(); batchTracks = songs.distinctBy { it.id }; anchor = bounds
        additions.reset(batchTracks)
        editing = false; choosingArtist = false; expanded = true; choosing = false; message = null
        if (chooseTargets) choose()
    }

    fun openEditor() {
        val song = track ?: return
        launchRequest {
            val store = requireNotNull(library)
            val overrides = withContext(Dispatchers.IO) { store.overrides(song.id) }
            editing = true
            editForm = LibraryEditForm("编辑歌曲资料", listOf("曲名（留空恢复默认）", "创作者（留空恢复默认）"),
                listOf(overrides.first.orEmpty(), overrides.second.orEmpty())) { values ->
                val artwork = withContext(Dispatchers.IO) { store.overrides(song.id).third }
                store.updateOverrides(song.id, values[0].takeIf(String::isNotBlank), values[1].takeIf(String::isNotBlank), artwork)
            }
        }
    }

    fun submitEditor() {
        val form = editForm ?: return
        launchRequest { form.save(form.values.map { it.value }); editing = false; editForm = null; message = "资料已保存" }
    }

    fun open(song: MusicTrack, bounds: Rect, fromLyricsTitle: Boolean = false) {
        if (busy) return
        this.fromLyricsTitle = fromLyricsTitle
        track = song
        additions.reset(listOf(song))
        batchTracks = emptyList()
        editing = false
        editForm = null
        anchor = bounds
        message = null
        choosing = false
        choosingArtist = false
        expanded = true
        if (!fromLyricsTitle && song.source == MusicSource.QQ && (song.artistRefs.isEmpty() || song.albumMid.isBlank())) launchRequest {
            val resolved = withContext(Dispatchers.IO) {
                QqApiClient().enrichTrackMetadata(song, preferences.readSession(MusicSource.QQ).credential)
            }
            if (track?.id == song.id) track = resolved
        }
    }

    fun dismiss() { if (!busy) expanded = false }
    fun back() { if (!busy) { if (editing) { editing = false; editForm = null } else if (choosingArtist) choosingArtist = false else if (choosing && batchTracks.isEmpty()) choosing = false else dismiss() } }

    fun choose() {
        choosing = true
        launchRequest {
            val local = library?.state?.value?.manualAdditionTargets().orEmpty()
            targets = local
            val sources = (batchTracks.ifEmpty { listOfNotNull(track) }).map { it.source }.distinct()
            val remote = buildList {
                for (source in sources.filter { it in listOf(MusicSource.QQ, MusicSource.NETEASE, MusicSource.KUGOU) }) {
                    try {
                        addAll(withContext(Dispatchers.IO) {
                            val session = preferences.readSession(source)
                            if (source == MusicSource.QQ) repository.targets(session) else onlineRepository.targets(session)
                        })
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "部分平台歌单读取失败，仍可选择已加载的歌单" }
                }
            }
            targets = (local + remote).distinctBy { it.id }
            if (targets.isEmpty()) message = "暂无可添加的自建歌单"
        }
    }

    fun toggleTarget(target: MusicPlaylist, onChanged: (MusicPlaylist) -> Unit) {
        if (busy) return
        val live = additionTarget(target.id) ?: target
        val songs = playlistAdditionCandidates(live, additions.selected)
        val present = additions.members(live) - if (removals.playlistId == target.id) removals.ids else emptySet()
        val add = !songs.all { it.id in present }
        if (removals.playlistId == target.id) {
            songs.forEach { removals.set(it, !add) }
            return
        }
        val selected = if (add) songs.filterNot { it.id in present } else songs.filter { it.id in present }
        if (selected.isEmpty()) return
        val writer = PlaylistSongWriter(preferences, requireNotNull(library))
        val session = preferences.readSession(target.source)
        writingTarget = target.id
        launchRequest {
            var completed = 0
            try {
                if (target.isManagedLibrary && add || target.source == MusicSource.QQ && !target.isManagedLibrary) {
                    writer.changeAll(live, selected, add, session)
                    additions.confirm(target.id, selected, add)
                    completed = selected.size
                } else {
                    for (song in selected) {
                        writer.change(live, song, add, session)
                        additions.confirm(target.id, listOf(song), add)
                        completed++
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                throw PlatformApiException("已${if (add) "添加" else "移除"} $completed 首，其余保留：${error.asUserMessage()}")
            } finally {
                writingTarget = null
                if (completed > 0 && !target.isManagedLibrary && preferences.credential(target.source) == session.credential) onChanged(target)
            }
        }
    }

    private fun additionTarget(id: String): MusicPlaylist? =
        library?.state?.value?.playlists?.firstOrNull { it.id == id } ?: targets.firstOrNull { it.id == id }

    private fun launchRequest(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.asUserMessage() }
            finally { busy = false }
        }
    }
}

internal val LocalQqPlaylistSongMenu = staticCompositionLocalOf<QqPlaylistSongMenuState?> { null }
