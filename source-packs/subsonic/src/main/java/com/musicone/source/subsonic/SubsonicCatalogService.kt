package com.musicone.source.subsonic

import com.musicone.source.sdk.*
import com.musicone.source.subsonic.internal.*

/** 首页、每日推荐、搜索与播放记录；资料全部来自当前服务器。首页不提供相似推荐货架。 */
internal class SubsonicCatalogService(
    private val requests: SubsonicRequests,
    private val client: SubsonicClient,
    private val urls: SubsonicArtworkUrls,
    private val home: SubsonicHomeFeed,
    private val daily: SubsonicDailyStore,
) : SourceDiscovery, SourceDailyRecommendations, SourceHomeSectionPresentation, SourceSearch {
    override val supportsRecent = true
    override val kinds = setOf(SourceSearchKind.TRACK, SourceSearchKind.ALBUM, SourceSearchKind.ARTIST)

    override suspend fun home(request: SourceCursorRequest): SourcePage<SourceHomeSection> =
        requests.current { server -> home.page(server, request.cursor) }

    override fun homePresentation(section: SourceHomeSection): SourceHomePresentation = home.presentation(section)

    /** 当天已抽过就沿用，保证“每日”不随刷新变化；当天没有记录才重新抽。 */
    override suspend fun dailyRecommendations(policy: SourceFetchPolicy): List<SourceTrackDetails> = requests.current { server ->
        val songs = daily.read(server.key) ?: home.randomSongs(server, DAILY_SIZE).also { daily.save(server.key, it) }
        songs.map { it.details(server, urls) }
    }

    /** 最近播放只有专辑维度，Subsonic 不记录单曲播放历史。 */
    override suspend fun recentSnapshot(): SourceRecentSnapshot = requests.current { server ->
        val albums = client.get(server, "getAlbumList2", listOf("type" to "recent", "size" to "20"))
            .optJSONObject("albumList2")?.objects("album").orEmpty().mapNotNull { it.album() }
        SourceRecentSnapshot(albums = albums.map { it.collection(server, urls) })
    }

    override suspend fun recent(request: SourceCursorRequest): SourcePage<SourceTrackDetails> =
        throw UnsupportedOperationException("Subsonic 服务器不记录单曲播放历史")

    /** 开始播放时上报“正在播放”；计入播放次数在播放会话里达到时长后再提交。 */
    override suspend fun reportPlayed(track: SourceTrackDetails, playedMs: Long) {
        if (playedMs <= 0) return
        val ref = track.subsonicRef()
        requests.forServer(ref.serverKey) { server ->
            client.get(server, "scrobble", listOf("id" to ref.id, "submission" to "false")); Unit
        }
    }

    override suspend fun search(query: String, kind: SourceSearchKind, request: SourceCursorRequest): SourceSearchResult =
        requests.current { server ->
            val offset = request.cursor?.toIntOrNull() ?: 0
            val limit = request.limit.coerceIn(1, 100)
            val counts = listOf("song", "album", "artist").flatMap { type ->
                val wanted = when (kind) {
                    SourceSearchKind.TRACK -> "song"; SourceSearchKind.ALBUM -> "album"; SourceSearchKind.ARTIST -> "artist"
                    else -> throw UnsupportedOperationException("不支持此搜索类型")
                }
                listOf("${type}Count" to (if (type == wanted) limit else 0).toString(), "${type}Offset" to (if (type == wanted) offset else 0).toString())
            }
            val result = client.get(server, "search3", listOf("query" to query.trim()) + counts).optJSONObject("searchResult3")
            when (kind) {
                SourceSearchKind.TRACK -> {
                    val songs = result?.objects("song").orEmpty().mapNotNull { it.song() }
                    SourceSearchResult(tracks = songs.map { it.details(server, urls) }, nextCursor = next(offset, songs.size, limit))
                }
                SourceSearchKind.ALBUM -> {
                    val albums = result?.objects("album").orEmpty().mapNotNull { it.album() }
                    SourceSearchResult(collections = albums.map { it.collection(server, urls) }, nextCursor = next(offset, albums.size, limit))
                }
                else -> {
                    val artists = result?.objects("artist").orEmpty().mapNotNull { it.artist() }
                    SourceSearchResult(entities = artists.map { it.entity(server, urls) }, nextCursor = next(offset, artists.size, limit))
                }
            }
        }

    private fun next(offset: Int, size: Int, limit: Int) = (offset + size).takeIf { size >= limit }?.toString()

    private companion object { const val DAILY_SIZE = 30 }
}

/** 歌手、专辑详情；歌手的歌曲按专辑逐页展开，Subsonic 没有“歌手全部歌曲”接口。 */
internal class SubsonicEntityService(
    private val requests: SubsonicRequests,
    private val client: SubsonicClient,
    private val urls: SubsonicArtworkUrls,
) : SourceEntities, SourceEntityArtwork {
    override val kinds = setOf("artist", "album")

    private fun artistAlbums(server: SubsonicServer, id: String): Pair<SubsonicArtist, List<SubsonicAlbum>> {
        val value = client.get(server, "getArtist", listOf("id" to id)).optJSONObject("artist")
            ?: throw SubsonicApiException(70, "服务器上找不到该歌手")
        val artist = value.artist() ?: throw SubsonicApiException(70, "服务器上找不到该歌手")
        // 新专辑在前，同年份保持服务器顺序。
        val albums = value.objects("album").mapNotNull { it.album() }.withIndex()
            .sortedWith(compareByDescending<IndexedValue<SubsonicAlbum>> { it.value.year }.thenBy { it.index }).map { it.value }
        return artist to albums
    }

    private fun albumSongs(server: SubsonicServer, id: String): Pair<SubsonicAlbum, List<SubsonicSong>> {
        val value = client.get(server, "getAlbum", listOf("id" to id)).optJSONObject("album")
            ?: throw SubsonicApiException(70, "服务器上找不到该专辑")
        val album = value.album() ?: throw SubsonicApiException(70, "服务器上找不到该专辑")
        return album to value.objects("song").mapNotNull { it.song() }.inAlbumOrder()
    }

    override suspend fun entity(reference: SourceEntityRef): SourceEntity = requests.forServer(reference.serverKey()) { server ->
        when (reference.kind) {
            "artist" -> {
                val (artist, albums) = artistAlbums(server, reference.id)
                val info = runCatching { client.get(server, "getArtistInfo2", listOf("id" to reference.id)).optJSONObject("artistInfo2") }.getOrNull()
                val biography = info?.optString("biography").orEmpty().let(::plainText)
                val image = info?.optString("largeImageUrl")?.takeIf(String::isNotBlank)
                val trackCount = albums.mapNotNull { it.songCount }.takeIf { it.size == albums.size }?.sum()
                artist.copy(albumCount = artist.albumCount ?: albums.size).entity(server, urls, biography, image, trackCount)
            }
            "album" -> {
                val (album, songs) = albumSongs(server, reference.id)
                SourceEntity(SourceEntityRef(album.id, album.name, "album", reference.payload),
                    listOfNotNull(album.artist.takeIf(String::isNotBlank), album.year.takeIf { it > 0 }?.toString()).joinToString(" · "),
                    subsonicArtwork(urls.cover(server, album.coverArt), album.name.take(1)),
                    mapOf("trackCount" to songs.size.toString()))
            }
            else -> throw UnsupportedOperationException("不支持此实体")
        }
    }

    override suspend fun artwork(reference: SourceEntityRef): SourceArtwork = requests.forServer(reference.serverKey()) { server ->
        val cover = when (reference.kind) {
            "artist" -> client.get(server, "getArtist", listOf("id" to reference.id)).optJSONObject("artist")?.artist()
                ?.let { urls.cover(server, it.coverArt) ?: it.artistImageUrl.takeIf(String::isNotBlank) }
            "album" -> client.get(server, "getAlbum", listOf("id" to reference.id)).optJSONObject("album")?.album()
                ?.let { urls.cover(server, it.coverArt) }
            else -> null
        }
        subsonicArtwork(cover, reference.name.take(1))
    }

    /** 游标是下一张专辑的序号；每页至少取满 limit 首或取完全部专辑，不交出空的中间页。 */
    override suspend fun tracks(reference: SourceEntityRef, request: SourceCursorRequest): SourcePage<SourceTrackDetails> =
        requests.forServer(reference.serverKey()) { server ->
            when (reference.kind) {
                "album" -> {
                    val songs = albumSongs(server, reference.id).second
                    val offset = request.cursor?.toIntOrNull() ?: 0
                    val selected = songs.drop(offset).take(request.limit.coerceAtLeast(1))
                    SourcePage(selected.map { it.details(server, urls) }, (offset + selected.size).takeIf { it < songs.size }?.toString())
                }
                "artist" -> {
                    val albums = artistAlbums(server, reference.id).second
                    var index = request.cursor?.toIntOrNull() ?: 0
                    val collected = mutableListOf<SubsonicSong>()
                    while (index < albums.size && collected.size < request.limit.coerceAtLeast(1)) {
                        collected += albumSongs(server, albums[index].id).second
                        index++
                    }
                    SourcePage(collected.map { it.details(server, urls) }, index.takeIf { it < albums.size && collected.isNotEmpty() }?.toString())
                }
                else -> throw UnsupportedOperationException("不支持此实体")
            }
        }

    override suspend fun collections(reference: SourceEntityRef, request: SourceCursorRequest): SourcePage<SourceCollection> =
        requests.forServer(reference.serverKey()) { server ->
            require(reference.kind == "artist") { "只有歌手提供专辑列表" }
            val albums = artistAlbums(server, reference.id).second
            val offset = request.cursor?.toIntOrNull() ?: 0
            val selected = albums.drop(offset).take(request.limit.coerceAtLeast(1))
            SourcePage(selected.map { it.collection(server, urls, listOfNotNull(it.year.takeIf { y -> y > 0 }?.toString()).joinToString()) },
                (offset + selected.size).takeIf { it < albums.size }?.toString())
        }
}

/** 歌手简介常带 HTML 链接，转成纯文本（不依赖 API 24 的 Html 新接口）。 */
internal fun plainText(value: String): String = value
    .replace(Regex("(?i)<br\\s*/?>"), "\n")
    .replace(Regex("<[^>]+>"), "")
    .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
    .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    .trim()
