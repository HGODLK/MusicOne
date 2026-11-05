package com.musicone

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.musicone.source.sdk.SourceCollectionSortOption
import com.musicone.sourceintegration.*

internal data class PlaylistSortSelection(val value: PlaylistSort, val select: (PlaylistSort) -> Unit,
    val options: List<SourceCollectionSortOption> = emptyList(), val selectedId: String? = null,
    val selectId: (String) -> Unit = {})

/** 包保留自己的排序键与选项，宿主不读取平台目录号或私有偏好；包没有提供时由宿主保存。 */
@Composable
internal fun rememberPlaylistSortSelection(playlist: MusicPlaylist, accountId: String?, createdPlaylists: List<MusicPlaylist>): PlaylistSortSelection {
    val context = LocalContext.current
    val service = SourcePackageController.get(context).running(playlist.source.id)?.entry?.collections
    val collection = playlist.toSourceCollection(accountId)
    val sourceOptions = service?.sortOptions(collection).orEmpty()
    val hostStore = remember(context) { HostPlaylistSortStore(context) }
    val hostKey = hostPlaylistSortKey(playlist, accountId, sourceOptions)
    val options = if (hostKey != null) HOST_PLAYLIST_SORT_OPTIONS else sourceOptions
    // 账号就绪后音源可能开始提供排序，键里带上 hostKey 才会切换保存位置。
    var selected by rememberSaveable(playlist.source.id, playlist.id, accountId, hostKey) {
        mutableStateOf(if (hostKey != null) hostStore.read(hostKey) else service?.selectedSort(collection))
    }
    val selectId: (String) -> Unit = { id ->
        require(options.any { it.id == id }) { "音源未提供此排序" }
        if (hostKey != null) hostStore.write(hostKey, id) else service?.selectSort(collection, id)
        selected = id
    }
    return PlaylistSortSelection(PlaylistSort.entries.firstOrNull { it.name == selected } ?: PlaylistSort.ADDED_DESC,
        { if (options.any { option -> option.id == it.name }) selectId(it.name) }, options, selected, selectId)
}

/** 详情页之外的播放入口（歌单卡片）读取同一份排序，整单顺序与详情页一致。 */
internal fun storedPlaylistSort(context: Context, playlist: MusicPlaylist, accountId: String?): PlaylistSort {
    val service = SourcePackageController.get(context).running(playlist.source.id)?.entry?.collections
    val collection = playlist.toSourceCollection(accountId)
    val hostKey = hostPlaylistSortKey(playlist, accountId, service?.sortOptions(collection).orEmpty())
    val selected = if (hostKey != null) HostPlaylistSortStore(context).read(hostKey) else service?.selectedSort(collection)
    return PlaylistSort.entries.firstOrNull { it.name == selected } ?: PlaylistSort.ADDED_DESC
}

internal fun MusicPlaylist.sortedForPlayback(context: Context, accountId: String?): MusicPlaylist =
    copy(tracks = sortedPlaylistTracks(tracks, storedPlaylistSort(context, this, accountId)))

@Composable
internal fun rememberSortedPlaylistPlay(accountId: String?, play: (MusicPlaylist) -> Unit): (MusicPlaylist) -> Unit {
    val context = LocalContext.current.applicationContext
    val currentAccount by rememberUpdatedState(accountId)
    val currentPlay by rememberUpdatedState(play)
    return remember(context) { { playlist -> currentPlay(playlist.sortedForPlayback(context, currentAccount)) } }
}
