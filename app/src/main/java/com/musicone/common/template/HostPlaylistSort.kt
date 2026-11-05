package com.musicone

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.musicone.source.sdk.SourceCollectionSortOption

private const val DAILY_PLAYLIST_SORT_FLAG = "templateDailySort"

/** 宿主生成的每日推荐：歌单 ID 带日期，需要单独标记才能跨天沿用排序。 */
internal val DAILY_PLAYLIST_PROVIDER_DATA = mapOf("templateEphemeral" to "true", DAILY_PLAYLIST_SORT_FLAG to "true")

internal val HOST_PLAYLIST_SORT_OPTIONS = PlaylistSort.entries.map { SourceCollectionSortOption(it.name, it.label) }

/** 音源提供完整的三种排序时用音源的，否则由宿主按歌单保存，排序按钮不会失效。 */
internal fun hostPlaylistSortKey(playlist: MusicPlaylist, accountId: String?, sourceOptions: List<SourceCollectionSortOption>): String? {
    if (PlaylistSort.entries.all { sort -> sourceOptions.any { it.id == sort.name } }) return null
    // 本地歌单不属于任何账号，切换主音源后排序不变。
    val account = accountId.takeUnless { playlist.source == MusicSource.LOCAL }.orEmpty()
    return if (playlist.providerData[DAILY_PLAYLIST_SORT_FLAG] == "true") "${playlist.source.id}:$account"
    else "playlist:${playlist.source.id}:${account.length}:$account:${playlist.id}"
}

internal class HostPlaylistSortStore(context: Context) {
    // 沿用每日推荐时期的文件名，保留已有选择。
    private val preferences = context.applicationContext.getSharedPreferences("daily_playlist_sort", Context.MODE_PRIVATE)
    fun read(key: String): String? = preferences.getString(key, null)
    fun write(key: String, sort: String) = preferences.edit().putString(key, sort).apply()
}

/** 不对应任何音源歌单的列表（如全部缓存歌曲）直接按固定键保存排序。 */
@Composable
internal fun rememberHostPlaylistSort(key: String): PlaylistSortSelection {
    val context = LocalContext.current
    val store = remember(context) { HostPlaylistSortStore(context) }
    var selected by rememberSaveable(key) { mutableStateOf(store.read(key)) }
    return PlaylistSortSelection(PlaylistSort.entries.firstOrNull { it.name == selected } ?: PlaylistSort.ADDED_DESC, {
        store.write(key, it.name)
        selected = it.name
    }, HOST_PLAYLIST_SORT_OPTIONS, selected)
}
