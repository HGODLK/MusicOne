package com.musicone

import android.content.Context
import com.musicone.source.sdk.*
import com.musicone.sourceintegration.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** 搜索页面只持有通用资料，分类请求和游标完全交给当前音源。 */
internal class MainSourceSearchRepository(private val context: Context) {
    var source: MusicSource = PlatformPreferences(context).selectedSource()
        set(value) { if (field != value) cursors.clear(); field = value }
    private val cursors = mutableMapOf<Triple<String, MainSourceSearchTab, Int>, String?>()
    private fun service() = SourcePackageController.get(context).running(source.id)?.entry?.search
    val tabs: List<MainSourceSearchTab> get() {
        // 首次组合可能早于音源配置，未启用搜索时不创建分类或请求。
        val kinds = service()?.kinds.orEmpty()
        val supported = MainSourceSearchTab.entries.drop(1).filter { it.sdkKind() in kinds }
        return if (supported.isEmpty()) emptyList() else listOf(MainSourceSearchTab.ALL) + supported
    }
    suspend fun search(query: String, tab: MainSourceSearchTab, page: Int): MainSourceSearchPage = coroutineScope {
        val search = requireNotNull(service()) { "此音源未启用搜索" }
        val tabs = (if (tab == MainSourceSearchTab.ALL) MainSourceSearchTab.entries.drop(1) else listOf(tab)).filter { it.sdkKind() in search.kinds }
        val results = tabs.map { selected -> async {
            val key = Triple(query, selected, page)
            val cursor = if (page <= 1) null else cursors[key] ?: error("搜索分页已失效，请重新搜索")
            val result = search.search(query, selected.sdkKind(), SourceCursorRequest(cursor))
            cursors[Triple(query, selected, page + 1)] = result.nextCursor
            MainSourceSearchPage(result.tracks.map { it.toMusicTrack() }, result.entities.filter { it.reference.kind == "artist" }.map {
                MainSourceArtist(it.reference.id, it.reference.name, it.artwork.url, source = source, reference = it.reference)
            }, if (selected == MainSourceSearchTab.ALBUM) result.collections.map { it.toMusicPlaylist().let { playlist ->
                playlist.copy(providerData = playlist.providerData + ("templateEntityKind" to "album"))
            } } + result.entities.filter { it.reference.kind == "album" }.map { it.asTemplatePlaylist(source) } else emptyList(),
                if (selected == MainSourceSearchTab.PLAYLIST) result.collections.map { it.toMusicPlaylist() } else emptyList(), page,
                result.nextCursor != null)
        } }.awaitAll()
        results.fold(MainSourceSearchPage(page = page)) { old, next -> old.copy(songs = old.songs + next.songs,
            singers = old.singers + next.singers, albums = old.albums + next.albums, playlists = old.playlists + next.playlists,
            hasMore = tab != MainSourceSearchTab.ALL && next.hasMore) }
    }
    val supportsHotSearch get() = (service() as? SourceHotSearch)?.supportsHotSearch == true
    suspend fun hotSearches() = (service() as? SourceHotSearch)?.hotSearches().orEmpty().filter(String::isNotBlank).distinct()
    suspend fun suggest(query: String): List<String> = service()?.let { if (it.supportsSuggestions) it.suggestions(query) else emptyList() }.orEmpty()
    private fun MainSourceSearchTab.sdkKind() = when(this) {
        MainSourceSearchTab.SONG, MainSourceSearchTab.ALL -> SourceSearchKind.TRACK
        MainSourceSearchTab.SINGER -> SourceSearchKind.ARTIST
        MainSourceSearchTab.ALBUM -> SourceSearchKind.ALBUM
        MainSourceSearchTab.PLAYLIST -> SourceSearchKind.COLLECTION
    }
}
