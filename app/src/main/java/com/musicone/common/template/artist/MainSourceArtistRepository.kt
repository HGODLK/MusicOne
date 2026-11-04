package com.musicone

import android.content.Context
import com.musicone.source.sdk.*
import com.musicone.sourceintegration.*
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class MainSourceEntityPage<T>(val items: List<T>, val next: Int?, val total: Int)
internal data class MainSourceArtistProfile(val name: String, val artwork: String?, val introduction: String,
    val backgrounds: List<String> = emptyList(), val trackCount: Int? = null)

/** 歌手页面的分页只保存公开 SDK 游标，不能构造平台协议。 */
internal class MainSourceArtistRepository(context: Context, private val artist: MainSourceArtist) {
    private val controller = SourcePackageController.get(context)
    private fun service() = requireNotNull(controller.running(artist.source.id)?.entry?.entities) { "此音源未启用歌手资料" }
    private val reference get() = artist.reference ?: SourceEntityRef(artist.id, artist.name, "artist")
    private val cursors = mutableMapOf<Triple<String, String, Int>, String?>()
    val sortOptions get() = service().trackSortOptions(reference)
    val supportsSearch get() = service().supportsTrackSearch(reference)
    @Volatile private var entity: SourceEntity? = null
    private val detailsMutex = Mutex()
    suspend fun songs(mid: String, begin: Int, sort: SourceCollectionSortOption?): MainSourceEntityPage<MusicTrack> {
        val key = Triple("songs", sort?.id.orEmpty(), begin)
        val cursor = if (begin == 0) null else cursors[key] ?: error("歌手歌曲分页已失效")
        val provider = service()
        val page = if (sort != null) provider.searchTracks(reference, "", sort.id, SourceCursorRequest(cursor))
            else provider.tracks(reference, SourceCursorRequest(cursor))
        val next = page.nextCursor?.let { cursorValue ->
            check(page.items.isNotEmpty() && cursorValue != cursor) { "歌手歌曲分页没有前进" }
            (begin + page.items.size).also { cursors[Triple("songs", sort?.id.orEmpty(), it)] = cursorValue }
        }
        return MainSourceEntityPage(page.items.map { it.toMusicTrack() }, next,
            entity?.attributes?.get("trackCount")?.toIntOrNull() ?: (begin + page.items.size))
    }
    suspend fun albums(mid: String, begin: Int): MainSourceEntityPage<MusicPlaylist> {
        val key = Triple("albums", "", begin)
        val cursor = if (begin == 0) null else cursors[key] ?: error("歌手专辑分页已失效")
        val page = service().collections(reference, SourceCursorRequest(cursor))
        val next = page.nextCursor?.let { cursorValue ->
            check(page.items.isNotEmpty() && cursorValue != cursor) { "歌手专辑分页没有前进" }
            (begin + page.items.size).also { cursors[Triple("albums", "", it)] = cursorValue }
        }
        return MainSourceEntityPage(page.items.map { it.toMusicPlaylist() }, next,
            entity?.attributes?.get("collectionCount")?.toIntOrNull() ?: (begin + page.items.size))
    }
    suspend fun searchSongs(singer: MainSourceArtist, profile: MainSourceArtistProfile, query: String, offset: Int,
        songTotal: Int, sort: SourceCollectionSortOption?, excludedSongIds: List<String>, customInfo: JSONObject?): MainSourceArtistSearchPage {
        val key = Triple(query, sort?.id.orEmpty(), offset)
        val cursor = if (offset == 0) null else cursors[key] ?: error("歌手搜索分页已失效")
        val page = service().searchTracks(reference, query, sort?.id, SourceCursorRequest(cursor))
        val next = page.nextCursor?.let { cursorValue ->
            check(page.items.isNotEmpty() && cursorValue != cursor) { "歌手搜索分页没有前进" }
            (offset + page.items.size).also { cursors[Triple(query, sort?.id.orEmpty(), it)] = cursorValue }
        }
        return MainSourceArtistSearchPage(page.items.map { it.toMusicTrack() }, next, offset + page.items.size, JSONObject())
    }
    // 头像资料和简介并行启动时共享详情，避免重复请求同一歌手及总数。
    private suspend fun details(): SourceEntity = detailsMutex.withLock {
        entity ?: service().entity(reference).also { entity = it }
    }
    suspend fun header(singer: MainSourceArtist): MainSourceArtistProfile {
        val value = details()
        val backgrounds = value.attributes["backgrounds"]?.let { raw -> runCatching {
            val values = JSONArray(raw); (0 until values.length()).map(values::getString)
        }.getOrNull() }.orEmpty()
        return MainSourceArtistProfile(value.reference.name, value.artwork.url, value.description, backgrounds,
            value.attributes["trackCount"]?.toIntOrNull()?.takeIf { it >= 0 })
    }
    suspend fun introduction(singer: MainSourceArtist): String = details().description
}
