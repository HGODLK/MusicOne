package com.musicone

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.musicone.source.sdk.SourceCollectionSortOption
import com.musicone.sourceintegration.*

internal data class PlaylistSortSelection(val value: PlaylistSort, val select: (PlaylistSort) -> Unit,
    val options: List<SourceCollectionSortOption> = emptyList(), val selectedId: String? = null,
    val selectId: (String) -> Unit = {})

/** 包保留自己的排序键与选项，宿主不读取平台目录号或私有偏好。 */
@Composable
internal fun rememberPlaylistSortSelection(playlist: MusicPlaylist, accountId: String?, createdPlaylists: List<MusicPlaylist>): PlaylistSortSelection {
    val context = LocalContext.current
    val service = SourcePackageController.get(context).running(playlist.source.id)?.entry?.collections
    val collection = playlist.toSourceCollection(accountId)
    val sourceOptions = service?.sortOptions(collection).orEmpty()
    val dailyStore = remember(context) { DailyPlaylistSortStore(context) }
    val dailyKey = dailyPlaylistSortKey(playlist, accountId, sourceOptions)
    val options = if (dailyKey != null) DAILY_PLAYLIST_SORT_OPTIONS else sourceOptions
    var selected by rememberSaveable(playlist.source.id, playlist.id, accountId) {
        mutableStateOf(if (dailyKey != null) dailyStore.read(dailyKey) else service?.selectedSort(collection))
    }
    val selectId: (String) -> Unit = { id ->
        require(options.any { it.id == id }) { "音源未提供此排序" }
        if (dailyKey != null) dailyStore.write(dailyKey, id) else service?.selectSort(collection, id)
        selected = id
    }
    return PlaylistSortSelection(PlaylistSort.entries.firstOrNull { it.name == selected } ?: PlaylistSort.ADDED_DESC,
        { if (options.any { option -> option.id == it.name }) selectId(it.name) }, options, selected, selectId)
}
