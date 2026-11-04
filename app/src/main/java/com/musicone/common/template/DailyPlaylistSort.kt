package com.musicone

import android.content.Context
import com.musicone.source.sdk.SourceCollectionSortOption

private const val DAILY_PLAYLIST_SORT_FLAG = "templateDailySort"

/** 宿主生成的每日推荐：音源没有它的排序键，由宿主保存选择。 */
internal val DAILY_PLAYLIST_PROVIDER_DATA = mapOf("templateEphemeral" to "true", DAILY_PLAYLIST_SORT_FLAG to "true")

internal val DAILY_PLAYLIST_SORT_OPTIONS = PlaylistSort.entries.map { SourceCollectionSortOption(it.name, it.label) }

/** 歌单 ID 带日期，键里不能带，否则第二天就恢复默认。音源自己提供排序时优先用音源的。 */
internal fun dailyPlaylistSortKey(playlist: MusicPlaylist, accountId: String?, sourceOptions: List<SourceCollectionSortOption>): String? =
    "${playlist.source.id}:${accountId.orEmpty()}".takeIf {
        sourceOptions.isEmpty() && playlist.providerData[DAILY_PLAYLIST_SORT_FLAG] == "true"
    }

internal class DailyPlaylistSortStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("daily_playlist_sort", Context.MODE_PRIVATE)
    fun read(key: String): String? = preferences.getString(key, null)
    fun write(key: String, sort: String) = preferences.edit().putString(key, sort).apply()
}
