package com.musicone.source.kugou

import com.musicone.source.sdk.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class KugouSourceCatalog(private val state: () -> KugouPackageState) : SourceDiscovery, SourceDailyRecommendations, SourceSearch,
    SourceCollections, SourceFavorites, SourceCollectionEditing, SourceCollectionCachePolicy, SourceCollectionReturnBehavior {
    override val refreshLibraryAfterAnyReturn = true
    // 账号缓存目录只接受云歌单，首页推荐和搜索歌单仍可正常浏览。
    override fun includesInCacheLibrary(collection: SourceCollection) = isKugouAccountCollection(collection)
    private val cached = ConcurrentHashMap<String, MusicPlaylist>()
    @Synchronized fun clearMemory() { cached.clear() }
    override val kinds: Set<SourceSearchKind> = setOf(SourceSearchKind.TRACK, SourceSearchKind.COLLECTION, SourceSearchKind.ALBUM)
    override val librarySections = SourceLibrarySection.entries.toSet()
    override val supportedOperations = setOf(SourceCollectionOperation.CHANGE_TRACKS)
    @Synchronized fun remember(playlist: MusicPlaylist, request: KugouAccountRequest): MusicPlaylist {
        state().check(request)
        return playlist.also { cached[it.id] = it }
    }
    private fun remember(value: SourceCollection) = cached[value.stableId] ?: KugouSourceMapping.collection(value)
    override suspend fun home(request: SourceCursorRequest): SourcePage<SourceHomeSection> = state().request { session ->
        val page = request.cursor?.toIntOrNull() ?: 0
        val pool = state().api.recommendedPlaylistPool(session.cookie)
        val lists = recommendationWindow(pool, 8, page).map { KugouSourceMapping.collection(remember(it, session), state().account()?.userId) }
        val tracks = state().api.recommendedTracks(session.cookie, page, pool).map { KugouSourceMapping.track(it, state().account()?.userId) }
        SourcePage(listOf(SourceHomeSection("library", "乐库", collections = lists), SourceHomeSection("recommendations", "为你推荐", tracks = tracks)), null)
    }
    override suspend fun dailyRecommendations(policy: SourceFetchPolicy): List<SourceTrackDetails> = state().request { session ->
        state().api.recommendedTracks(session.cookie, 0).map { KugouSourceMapping.track(it, state().account()?.userId) }
    }
    override suspend fun search(query: String, kind: SourceSearchKind, request: SourceCursorRequest): SourceSearchResult = state().request { session ->
        require(request.cursor == null) { "当前搜索结果已全部返回" }
        when (kind) {
            SourceSearchKind.TRACK -> SourceSearchResult(tracks = state().api.search(query, session.cookie).map { KugouSourceMapping.track(it, state().account()?.userId) })
            SourceSearchKind.COLLECTION, SourceSearchKind.ALBUM -> {
                val results = state().api.searchCollections(query, session.cookie).map { remember(it, session) }
                SourceSearchResult(collections = results.filter { it.remoteId().startsWith("album:") == (kind == SourceSearchKind.ALBUM) }
                    .map { KugouSourceMapping.collection(it, state().account()?.userId) })
            }
            else -> throw UnsupportedOperationException("酷狗未接入此搜索类型")
        }
    }
    override suspend fun library(section: SourceLibrarySection, request: SourceCursorRequest): SourcePage<SourceCollection> = state().request { session ->
        require(request.cursor == null)
        // 读取目录时不下载整份我喜欢；歌曲在打开、播放或缓存时按原详情链路加载。
        val library = state().sessions.library(session.cookie, loadFavoriteTracks = false)
        val lists = library.cacheCollections(section)
        SourcePage(lists.map { KugouSourceMapping.collection(remember(it, session), state().account()?.userId) }, null)
    }
    override suspend fun detail(collection: SourceCollection): SourceCollection = state().request { session ->
        KugouSourceMapping.collection(remember(state().api.playlistDetail(remember(collection), session.cookie), session), state().account()?.userId)
    }
    override suspend fun tracks(collection: SourceCollection, request: SourceCursorRequest): SourcePage<SourceTrackDetails> = state().request { session ->
        val cursor = request.cursor?.let { PlaylistTrackCursor(it.toInt()) }
        val result = state().api.playlistPage(remember(collection), session.cookie, cursor)
        SourcePage(result.playlist.tracks.map { KugouSourceMapping.track(it, state().account()?.userId) }, result.next?.offset?.toString())
    }
    override suspend fun favoriteTrackIds(): Set<String> = state().request { state().api.likedTrackIds(it.cookie) }
    override suspend fun isTrackFavorite(track: SourceTrackDetails): Boolean = track.stableId in favoriteTrackIds()
    override suspend fun setTrackFavorite(track: SourceTrackDetails, favorite: Boolean) = state().request { session ->
        state().api.setTrackLiked(session.cookie, KugouSourceMapping.track(track), favorite)
    }
    override suspend fun writableCollections(): List<SourceCollection> = state().request { session ->
        state().sessions.library(session.cookie, loadFavoriteTracks = false).created.map { KugouSourceMapping.collection(remember(it, session), state().account()?.userId) }
    }
    override suspend fun changeTracks(collection: SourceCollection, tracks: List<SourceTrackDetails>, add: Boolean) = state().request { session ->
        val target = state().sessions.library(session.cookie, loadFavoriteTracks = false).created.firstOrNull { it.id == collection.stableId }
            ?: error("只能修改当前账号创建的歌单，请刷新后重试")
        tracks.forEach { currentCoroutineContext().ensureActive(); state().check(session); state().sessions.changePlaylistTrack(session.cookie, target, KugouSourceMapping.track(it), add) }
        Unit
    }
    // 专辑属于实体资料；协议仍用原 album: 路由，不借用其他音源页面或接口。
    val entityService: SourceEntities = object : SourceEntities {
        override val kinds = setOf("album")
        override suspend fun entity(reference: SourceEntityRef): SourceEntity = SourceEntity(reference)
        override suspend fun tracks(reference: SourceEntityRef, request: SourceCursorRequest): SourcePage<SourceTrackDetails> {
            val id = if (reference.id.startsWith("kugou-")) reference.id else "kugou-album:${reference.id}"
            val collection = KugouSourceMapping.collection(cached[id] ?: MusicPlaylist(id, title = reference.name), state().account()?.userId)
            return this@KugouSourceCatalog.tracks(collection, request)
        }
        override suspend fun collections(reference: SourceEntityRef, request: SourceCursorRequest): SourcePage<SourceCollection> =
            throw UnsupportedOperationException("酷狗专辑未提供下属集合")
    }
}
