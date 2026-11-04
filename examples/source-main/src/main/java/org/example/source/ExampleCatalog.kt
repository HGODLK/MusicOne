package org.example.source

import com.musicone.source.sdk.*

/** 独立示例的内存目录，不访问商业平台，也不提供可播放地址。 */
internal class ExampleCatalog(private val sourceId: String) : SourceDiscovery,
    SourceCollections, SourceEntities {
    val artist = SourceEntity(SourceEntityRef("artist-demo", "示例歌手", "artist"), "虚拟歌手资料")
    val album = SourceEntity(SourceEntityRef("album-demo", "示例专辑", "album"), "虚拟专辑资料")
    val demoTracks = List(36) { index ->
        val remoteId = "track-${index + 1}"
        SourceTrackDetails(
            track = SourceTrack(TrackIdentity(sourceId, null, remoteId), "演示歌曲 ${index + 1}", listOf(artist.reference.name)),
            stableId = "$sourceId:$remoteId",
            album = album.reference.name,
            artists = listOf(artist.reference),
            albumRef = album.reference,
            durationMs = 180_000,
            access = TrackAccessInfo(playable = false, reason = "仅用于公共界面演示，不提供音频")
        )
    }
    val collection = SourceCollection(TrackIdentity(sourceId, null, "collection-demo"),
        "$sourceId:collection-demo", "演示歌单", description = "36 首虚拟歌曲，用于列表、多选与返回演示", count = demoTracks.size)

    // 按请求分页，末页返回空游标，示例不依赖账号或网络。
    private fun <T> page(items: List<T>, request: SourceCursorRequest): SourcePage<T> {
        val offset = request.cursor?.toIntOrNull()?.coerceIn(0, items.size) ?: 0
        val end = (offset + request.limit.coerceAtLeast(1)).coerceAtMost(items.size)
        return SourcePage(items.subList(offset, end), end.takeIf { it < items.size }?.toString())
    }

    override suspend fun home(request: SourceCursorRequest) = page(listOf(
        SourceHomeSection("demo", "虚拟目录演示", tracks = demoTracks.take(6),
            collections = listOf(collection), entities = listOf(artist, album))
    ), request)

    val search = object : SourceSearch {
        override val kinds = SourceSearchKind.entries.filter { it != SourceSearchKind.CONTENT }.toSet()
        override val supportsSuggestions = true
        override suspend fun suggestions(query: String) = listOf("演示", "示例歌手", "示例专辑").filter { it.contains(query) }
        override suspend fun search(query: String, kind: SourceSearchKind, request: SourceCursorRequest): SourceSearchResult {
            return when (kind) {
                SourceSearchKind.TRACK -> page(demoTracks.filter { it.track.title.contains(query) || it.artistsText.contains(query) }, request)
                    .let { SourceSearchResult(tracks = it.items, nextCursor = it.nextCursor) }
                SourceSearchKind.COLLECTION -> SourceSearchResult(collections = listOf(collection).filter { it.title.contains(query) })
                SourceSearchKind.ARTIST -> SourceSearchResult(entities = listOf(artist).filter { it.reference.name.contains(query) })
                SourceSearchKind.ALBUM -> SourceSearchResult(entities = listOf(album).filter { it.reference.name.contains(query) })
                SourceSearchKind.CONTENT -> throw UnsupportedOperationException("示例未声明内容搜索")
            }
        }
    }

    override val kinds = setOf("artist", "album")
    override suspend fun detail(collection: SourceCollection) = this.collection
    override suspend fun tracks(collection: SourceCollection, request: SourceCursorRequest) = page(demoTracks, request)
    override suspend fun entity(reference: SourceEntityRef) = when (reference.kind) {
        "artist" -> artist
        "album" -> album
        else -> throw UnsupportedOperationException("示例未声明此实体类型")
    }
    override suspend fun tracks(reference: SourceEntityRef, request: SourceCursorRequest) = page(demoTracks, request)
    override suspend fun collections(reference: SourceEntityRef, request: SourceCursorRequest) = page(listOf(collection), request)
}
