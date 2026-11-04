package com.musicone.sourceintegration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.musicone.*
import com.musicone.source.sdk.*

internal class SourcePublicTrackState(val access: SourcePublicComponentAccess, val search: PlaylistSearchState,
    val selection: PlaylistMultiSelection, private val sortState: MutableState<String?>,
    private val accountKey: Triple<SourceSessionStatus?, String?, Long?>) : SourceTrackListState {
    private val accountId get() = accountKey.second
    init { selection.canOperate = { access.check(); access.accountKey() == accountKey } }
    fun check() {
        access.check()
        if (access.accountKey() != accountKey) throw kotlinx.coroutines.CancellationException("账号已变化，请重新取得列表状态")
    }
    var originals = emptyMap<String, SourceTrackDetails>()
    fun register(track: SourceTrackDetails) {
        check(); access.check(track)
        val song = track.toMusicTrack()
        originals = originals + (song.id to track)
        selection.update((selection.tracks + song).distinctBy { it.id })
    }
    var collection: MusicPlaylist? = null
    private var lastInput: List<SourceTrackDetails>? = null
    fun update(tracks: List<SourceTrackDetails>, complete: Boolean = false) {
        check(); access.check(tracks)
        require(tracks.all { it.track.identity.accountId == null || it.track.identity.accountId == accountId }) { "列表包含其他账号的歌曲" }
        if (!complete && lastInput == tracks) return
        if (!complete) lastInput = tracks
        originals = tracks.associateBy { it.toMusicTrack().id }
        selection.update(tracks.map { it.toMusicTrack() })
    }
    override val sourceId get(): String { access.check(); return access.sourceId }
    override var query: String
        get() { check(); return search.query }
        set(value) { check(); search.query = value }
    override var selectedSortId: String?
        get() { check(); return sortState.value }
        set(value) { check(); sortState.value = value }
    override val selectionActive get(): Boolean { check(); return selection.active }
    override val selectedTrackIds get(): Set<String> { check(); return selection.ids.mapNotNullTo(hashSetOf()) { originals[it]?.stableId } }
    private fun internalId(trackId: String) = originals.entries.firstOrNull { it.value.stableId == trackId }?.key
        ?: error("歌曲不在当前列表中")
    override fun beginSelection(trackId: String) { check(); selection.begin(internalId(trackId)) }
    override fun toggleSelection(trackId: String) { check(); selection.toggle(internalId(trackId)) }
    override fun clearSelection() { check(); selection.close() }
}

internal class SourcePublicTrackComponents(private val access: SourcePublicComponentAccess) : SourceTrackComponents {
    override val sourceId get(): String { access.check(); return access.sourceId }
    private fun state(value: SourceTrackListState): SourcePublicTrackState {
        access.check()
        require(value.sourceId == sourceId) { "列表状态不属于当前音源" }
        return requireNotNull(value as? SourcePublicTrackState) { "列表状态必须由公共控件创建" }.also { it.check() }
    }

    @Composable override fun rememberTrackListState(key: String): SourceTrackListState {
        access.check()
        val accountKey = access.accountKey()
        val search = rememberPlaylistSearchState("component:$sourceId:$accountKey:$key")
        val selection = rememberSaveable(sourceId, accountKey, key, saver = listSaver<PlaylistMultiSelection, String>(
            save = { listOf(it.active.toString()) + it.ids },
            restore = { values -> PlaylistMultiSelection().apply { active = values.first() == "true"; ids = values.drop(1).toSet() } },
        )) { PlaylistMultiSelection() }
        val sort = rememberSaveable(sourceId, accountKey, key) { mutableStateOf<String?>(null) }
        return remember(access, search, selection, sort, accountKey) { SourcePublicTrackState(access, search, selection, sort, accountKey) }
    }

    @Composable override fun TrackListHost(collection: SourceCollection, state: SourceTrackListState,
        tracks: List<SourceTrackDetails>, bottomInsetDp: Float, onChanged: () -> Unit, content: @Composable () -> Unit) {
        access.check(collection); access.check(tracks)
        val handle = state(state)
        require(collection.identity.accountId == null || collection.identity.accountId == access.accountId()) { "歌单不属于当前账号上下文" }
        val mapped = remember(tracks) { tracks.map { it.toMusicTrack() } }
        val playlist = collection.toMusicPlaylist().copy(tracks = mapped)
        handle.collection = playlist
        val packageContext = LocalContext.current
        access.Host {
            val favorites = LocalMusicFavorites.current
            val queue = LocalPlaybackQueueActions.current.takeIf {
                SourcePackageController.get(access.context).running(sourceId)?.entry?.implementedCapabilities
                    ?.contains(SourceCapability.PLAYBACK) == true
            }
            SideEffect { handle.update(tracks) }
            CompositionLocalProvider(LocalPlaybackQueueActions provides queue) {
            key(access.accountKey()) {
            MainSourcePlaylistSongMenuHost(playlist, emptyList(), bottomInsetDp.dp,
                access.valueEvent { _: MusicTrack -> onChanged() }, access.valueEvent { _: MusicPlaylist -> onChanged() },
                access.valueEvent(favorites.toggle),
                externalSelection = handle.selection) {
                CompositionLocalProvider(LocalPlaylistMultiSelection provides handle.selection) {
                    SourceHostUiResources(packageContext, content)
                }
            }
            }
            }
        }
    }

    @Composable override fun TrackList(tracks: List<SourceTrackDetails>, state: SourceTrackListState,
        player: SourcePlayerState, favoriteIds: Set<String>, loading: Boolean, error: String?, modifier: Modifier,
        loadAll: (suspend () -> List<SourceTrackDetails>)?, hasMore: Boolean, onLoadMore: () -> Unit,
        onClick: (SourceTrackDetails) -> Unit, onFavorite: (SourceTrackDetails) -> Unit) {
        access.check(tracks)
        val handle = state(state)
        val mapped = remember(tracks) { tracks.map { it.toMusicTrack() } }
        val sourceById = remember(tracks) { tracks.associateBy { it.toMusicTrack().id } }
        val click = access.valueEvent(onClick)
        val favorite = access.valueEvent(onFavorite)
        val visible = mapped.matchingPlaylistQuery(handle.query)
        val sort = PlaylistSort.entries.firstOrNull { it.name == handle.selectedSortId }
        val sorted = if (sort == null) visible else sortedPlaylistTracks(visible, sort)
        val scroll = rememberLazyListState()
        access.Host {
            SideEffect { handle.update(tracks) }
            CompositionLocalProvider(LocalPlaylistMultiSelection provides handle.selection) {
                LazyColumn(modifier, state = scroll) {
                    playlistTrackItems(handle.collection?.copy(tracks = sorted) ?: libraryPlaylist("component:$sourceId", "歌曲", sorted),
                        MusicOneUiState(currentTrack = mapped.firstOrNull { it.id == player.stableTrackId }, isPlaying = player.isPlaying),
                        tracks.filter { it.stableId in favoriteIds || it.toMusicTrack().id in favoriteIds }.mapTo(hashSetOf()) { it.toMusicTrack().id },
                        { song -> sourceById[song.id]?.let(click) },
                        { song -> sourceById[song.id]?.let(favorite) }, loading = loading, loadMessage = error,
                        selectionLoadAll = loadAll?.let { load -> { handle.check(); load().also { handle.update(it, complete = true) }.map { it.toMusicTrack() } } })
                    item("source-component-load-more") { ArtistLoadMore(loading && tracks.isNotEmpty(), error, hasMore, access.event(onLoadMore)) }
                }
            }
        }
    }

    @Composable override fun SelectionRow(track: SourceTrackDetails, state: SourceTrackListState,
        current: Boolean, playing: Boolean, favorite: Boolean, modifier: Modifier, onClick: () -> Unit, onFavorite: () -> Unit) {
        access.check(track)
        val handle = state(state)
        access.Host {
            SideEffect { handle.register(track) }
            CompositionLocalProvider(LocalPlaylistMultiSelection provides handle.selection) {
                PlaylistTrackRow(track.toMusicTrack(), current, playing, favorite, false,
                    access.event(onClick), access.event(onFavorite), modifier)
            }
        }
    }

    @Composable override fun SelectionToolbar(state: SourceTrackListState, loadAll: (suspend () -> List<SourceTrackDetails>)?) {
        val handle = state(state)
        access.Host {
            CompositionLocalProvider(LocalPlaylistMultiSelection provides handle.selection) {
                PlaylistSelectionToolbar(loadAll = loadAll?.let { load -> { handle.check(); load().also { handle.update(it, complete = true) }.map { it.toMusicTrack() } } })
            }
        }
    }

    @Composable override fun SearchTools(state: SourceTrackListState, canLocate: Boolean, onLocate: () -> Unit, modifier: Modifier) {
        val handle = state(state)
        access.Host { PlaylistFloatingTools(handle.search, canLocate, access.event(onLocate), null, Rect.Zero,
            0.dp, { 1f }, modifier, inlinePlacement = true) }
    }

    @Composable override fun SortButton(options: List<SourceCollectionSortOption>, selectedId: String?, onSelect: (String) -> Unit) {
        access.Host {
            ArtistSongSortButton(options.firstOrNull { it.id == selectedId } ?: options.firstOrNull(), access.event {
                if (options.isNotEmpty()) onSelect(options[(options.indexOfFirst { it.id == selectedId } + 1) % options.size].id)
            })
        }
    }
}
