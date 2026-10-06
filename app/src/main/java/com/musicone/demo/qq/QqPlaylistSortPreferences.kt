package com.musicone.demo

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit

/** 只接受明确属于当前账号的自建歌单和固定的收藏入口，不按歌单标题判断。 */
internal fun qqPlaylistSortKey(
    playlist: MusicPlaylist,
    accountId: String?,
    createdPlaylists: List<MusicPlaylist>,
): String? {
    if (playlist.source != MusicSource.QQ || accountId.isNullOrBlank() || playlist.isQqSearchAlbum) return null
    val created = createdPlaylists.firstOrNull { it.source == MusicSource.QQ && it.id == playlist.id }
    val identity = when {
        playlist.id == QQ_FAVORITES_PLAYLIST_ID -> "favorites"
        playlist.id.startsWith(QQ_PROFILE_DIRECTORY_ID_PREFIX) -> playlist.id
        created != null -> created.qqDirectoryId?.let { "$QQ_PROFILE_DIRECTORY_ID_PREFIX$it" } ?: created.id
        else -> return null
    }
    return "${accountId.length}:$accountId:$identity"
}

internal fun playlistSortFromPreference(value: String?): PlaylistSort =
    PlaylistSort.entries.firstOrNull { it.name == value } ?: PlaylistSort.ADDED_DESC

internal class QqPlaylistSortPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("qq_playlist_sort", Context.MODE_PRIVATE)

    fun read(key: String?): PlaylistSort =
        playlistSortFromPreference(key?.let { preferences.getString(it, null) })

    fun write(key: String?, sort: PlaylistSort) {
        if (key != null) preferences.edit { putString(key, sort.name) }
    }
}

internal data class PlaylistSortSelection(val value: PlaylistSort, val select: (PlaylistSort) -> Unit)

@Composable
internal fun rememberPlaylistSortSelection(
    playlist: MusicPlaylist,
    qqAccountId: String?,
    createdPlaylists: List<MusicPlaylist>,
): PlaylistSortSelection {
    val context = LocalContext.current
    val preferences = remember(context) { QqPlaylistSortPreferences(context) }
    val key = qqPlaylistSortKey(playlist, qqAccountId, createdPlaylists)
    var sort by rememberSaveable(playlist.source, playlist.id, qqAccountId, key) {
        mutableStateOf(preferences.read(key))
    }
    return PlaylistSortSelection(sort) { selected ->
        sort = selected
        preferences.write(key, selected)
    }
}
