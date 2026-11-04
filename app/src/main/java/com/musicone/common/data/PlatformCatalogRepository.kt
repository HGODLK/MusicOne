package com.musicone

import android.content.Context
import com.musicone.source.sdk.*
import com.musicone.sourceintegration.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal enum class CatalogFetchPolicy { CACHE_FIRST, SERVER_VERIFY }

/** 在线目录只经过已激活音源 SDK；本地缓存由原存储层独立保留。 */
internal class PlatformCatalogRepository(context: Context) {
    private val controller = SourcePackageController.get(context)
    private val pages = mutableMapOf<Pair<String, Int>, SourcePage<SourceHomeSection>>()
    private val revisions = mutableMapOf<String, Pair<String?, Long>>()
    private val homeMutex = Mutex()
    private fun entry(source: MusicSource) = controller.running(source.id)?.entry ?: error("${source.label}音源未启用")
    private fun account(source: MusicSource) = entry(source).authentication?.currentAccount()
    suspend fun search(source: MusicSource, query: String): List<MusicTrack> =
        requireNotNull(entry(source).search) { "此音源不支持搜索" }.search(query, SourceSearchKind.TRACK, SourceCursorRequest()).tracks.map { it.toMusicTrack() }
    suspend fun searchCollections(source: MusicSource, query: String): List<MusicPlaylist> =
        requireNotNull(entry(source).search) { "此音源不支持搜索" }.search(query, SourceSearchKind.COLLECTION, SourceCursorRequest()).collections.map { it.toMusicPlaylist() }
    private suspend fun home(source: MusicSource, page: Int, policy: CatalogFetchPolicy): SourcePage<SourceHomeSection> = homeMutex.withLock {
        require(page >= 0)
        val service = entry(source)
        val state = service.authentication?.currentAccount()
        val revision = state?.account?.id to (state?.revision ?: 0L)
        if (revisions[source.id] != revision || policy == CatalogFetchPolicy.SERVER_VERIFY) {
            pages.keys.removeAll { it.first == source.id }; revisions[source.id] = revision
        }
        val discovery = requireNotNull(service.discovery) { "此音源不支持推荐" }
        for (index in 0..page) {
            val key = source.id to index
            if (pages.containsKey(key)) continue
            val previous = pages[source.id to (index - 1)]
            pages[key] = if (index > 0 && previous?.nextCursor == null) SourcePage(emptyList(), null)
            else discovery.home(SourceCursorRequest(previous?.nextCursor, policy = if (policy == CatalogFetchPolicy.SERVER_VERIFY) SourceFetchPolicy.REFRESH else SourceFetchPolicy.CACHE_FIRST))
        }
        check(service.authentication?.currentAccount()?.account?.id == state?.account?.id) { "账号已变化，请刷新" }
        requireNotNull(pages[source.id to page])
    }
    suspend fun recommendedPlaylists(source: MusicSource, page: Int) = home(source, page, CatalogFetchPolicy.CACHE_FIRST).items.flatMap { it.collections }.map { it.toMusicPlaylist() }
    suspend fun recommendedTracks(source: MusicSource, page: Int, policy: CatalogFetchPolicy = CatalogFetchPolicy.CACHE_FIRST): List<MusicTrack> {
        val discovery = requireNotNull(entry(source).discovery) { "此音源不支持推荐" }
        val fetchPolicy = if (policy == CatalogFetchPolicy.SERVER_VERIFY) SourceFetchPolicy.REFRESH else SourceFetchPolicy.CACHE_FIRST
        val tracks = (discovery as? SourceDailyRecommendations)?.dailyRecommendations(fetchPolicy)
            // 旧音源仅接受明确标注的每日推荐分区，绝不把普通信息流当成每日推荐。
            ?: legacyDailyRecommendationTracks(home(source, 0, policy).items)
        return tracks.map { it.toMusicTrack() }
    }
    suspend fun userPlaylists(source: MusicSource): List<MusicPlaylist> {
        val service = requireNotNull(entry(source).collections) { "此音源不支持资料库" }
        val result = mutableListOf<MusicPlaylist>()
        for (section in service.librarySections) {
            var cursor: String? = null
            do {
                val page = service.library(section, SourceCursorRequest(cursor))
                result += page.items.map { it.toMusicPlaylist() }
                check(page.nextCursor == null || page.nextCursor != cursor) { "音源资料库分页没有前进" }
                cursor = page.nextCursor
            } while (cursor != null)
        }
        return result.distinctBy(MusicPlaylist::id)
    }
    suspend fun playlistDetail(playlist: MusicPlaylist): MusicPlaylist {
        var result: PlaylistTrackPage? = null
        do { result = mergePlaylistTrackPage(result, playlistPage(playlist, result?.next)) } while (result.next != null)
        val complete = requireNotNull(result).playlist
        return complete.copy(count = complete.count.coerceAtLeast(complete.tracks.size))
    }
    suspend fun playlistPage(playlist: MusicPlaylist, cursor: PlaylistTrackCursor?): PlaylistTrackPage {
        val reference = playlist.templateEntityReference()
        if (reference != null) {
            val entities = requireNotNull(entry(playlist.source).entities) { "此音源不支持实体歌曲" }
            val detail = if (cursor == null) entities.entity(reference).let { entity -> playlist.copy(
                title = entity.reference.name, description = entity.description,
                artworkUrl = entity.artwork.url ?: playlist.artworkUrl,
                count = entity.attributes["trackCount"]?.toIntOrNull() ?: playlist.count) } else playlist
            val page = entities.tracks(reference, SourceCursorRequest(cursor?.sourceCursor, PLAYLIST_TRACK_PAGE_SIZE))
            val next = page.nextCursor?.let {
                check(it != cursor?.sourceCursor) { "音源实体歌曲分页没有前进" }
                PlaylistTrackCursor((cursor?.offset ?: 0) + page.items.size, sourceCursor = it)
            }
            return PlaylistTrackPage(detail.copy(tracks = page.items.map { it.toMusicTrack() }), next)
        }
        val service = requireNotNull(entry(playlist.source).collections) { "此音源不支持歌单" }
        val collection = playlist.toSourceCollection(account(playlist.source)?.account?.id)
        val detail = if (cursor == null) service.detail(collection) else collection
        val page = service.tracks(detail, SourceCursorRequest(cursor?.sourceCursor, PLAYLIST_TRACK_PAGE_SIZE))
        val next = page.nextCursor?.let {
            check(it != cursor?.sourceCursor) { "音源歌单分页没有前进" }
            PlaylistTrackCursor((cursor?.offset ?: 0) + page.items.size, sourceCursor = it)
        }
        val value = detail.toMusicPlaylist(page.items.map { it.toMusicTrack() })
        return PlaylistTrackPage(value.copy(providerData = value.providerData + playlist.providerData.filterKeys { it.startsWith("template") }), next)
    }
}

internal fun legacyDailyRecommendationTracks(sections: List<SourceHomeSection>): List<SourceTrackDetails> =
    sections.filter { it.payload.format == "musicone.home-section" &&
        runCatching { JSONObject(it.payload.encoded).optString("layout") == "daily" }.getOrDefault(false) }
        .flatMap { it.tracks }
