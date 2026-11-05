package org.example.source

import com.musicone.source.sdk.*

private data class DemoSongSpec(
    val title: String,
    val artist: SourceEntity,
    val albumName: String
)

/** 独立示例的内存目录，不访问商业平台，也不提供可播放地址。 */
internal class ExampleCatalog(private val sourceId: String) : SourceDiscovery,
    SourceCollections, SourceEntities {
    val artistRadwimps = SourceEntity(SourceEntityRef("artist-radwimps", "RADWIMPS", "artist"), "日本知名摇滚乐队")
    val artistLinkinPark = SourceEntity(SourceEntityRef("artist-lp", "Linkin Park", "artist"), "美国著名摇滚乐队")
    val artistOneRepublic = SourceEntity(SourceEntityRef("artist-1r", "OneRepublic", "artist"), "美国流行摇滚乐队")
    val artistMcDonalds = SourceEntity(SourceEntityRef("artist-mcdonalds", "麦当劳", "artist"), "麦门信徒的精神殿堂，麦门永存！")
    val artistRickAstley = SourceEntity(SourceEntityRef("artist-rick", "Rick Astley", "artist"), "理查德·保罗·阿斯特利，永不放弃你")
    val artistDouDou = SourceEntity(SourceEntityRef("artist-doudou", "DOUDOU", "artist"), "治愈系唱作女歌手")
    val artistZenless = SourceEntity(SourceEntityRef("artist-zzz", "绝区零", "artist"), "米哈游《绝区零》新艾利都官方原声")

    val artist = artistRadwimps
    val album = SourceEntity(SourceEntityRef("album-demo", "君の名は。", "album"), "RADWIMPS 经典专辑")

    private val allArtists = listOf(
        artistRadwimps, artistLinkinPark, artistOneRepublic,
        artistMcDonalds, artistRickAstley, artistDouDou, artistZenless
    )
    private val artistMap = allArtists.associateBy { it.reference.id }

    private val songSpecs = listOf(

        DemoSongSpec("前前前世", artistRadwimps, "君の名は。"),
        DemoSongSpec("スパークル", artistRadwimps, "君の名は。"),
        DemoSongSpec("なんでもないや", artistRadwimps, "君の名は。"),
        DemoSongSpec("夢灯籠", artistRadwimps, "君の名は。"),
        DemoSongSpec("愛にできることはまだあるかい", artistRadwimps, "天気の子"),
        DemoSongSpec("グランドエスケープ", artistRadwimps, "天気の子"),
        DemoSongSpec("大丈夫", artistRadwimps, "天気の子"),
        DemoSongSpec("祝祭", artistRadwimps, "天気の子"),
        DemoSongSpec("すずめ (feat. 十明)", artistRadwimps, "すずめの戸締まり"),
        DemoSongSpec("カナタハルカ", artistRadwimps, "すずめの戸締まり"),
        DemoSongSpec("有心論", artistRadwimps, "おかずのごはん"),
        DemoSongSpec("いいんですか?", artistRadwimps, "おかずのごはん"),
        DemoSongSpec("ふたりごと", artistRadwimps, "おかずのごはん"),
        DemoSongSpec("おしゃかしゃま", artistRadwimps, "アルトコロニーの定理"),
        DemoSongSpec("会心の一撃", artistRadwimps, "×と○と罪と"),
        DemoSongSpec("DADA", artistRadwimps, "絶体絶命"),
        DemoSongSpec("ます。", artistRadwimps, "RADWIMPS 4"),
        DemoSongSpec("正解", artistRadwimps, "正解"),
        DemoSongSpec("In the End", artistLinkinPark, "Hybrid Theory"),
        DemoSongSpec("Numb", artistLinkinPark, "Meteora"),
        DemoSongSpec("Faint", artistLinkinPark, "Meteora"),
        DemoSongSpec("Crawling", artistLinkinPark, "Hybrid Theory"),
        DemoSongSpec("What I've Done", artistLinkinPark, "Minutes to Midnight"),
        DemoSongSpec("Somewhere I Belong", artistLinkinPark, "Meteora"),
        DemoSongSpec("Bleed It Out", artistLinkinPark, "Minutes to Midnight"),
        DemoSongSpec("Counting Stars", artistOneRepublic, "Native"),
        DemoSongSpec("Apologize", artistOneRepublic, "Dreaming Out Loud"),
        DemoSongSpec("Secrets", artistOneRepublic, "Waking Up"),
        DemoSongSpec("Good Life", artistOneRepublic, "Waking Up"),
        DemoSongSpec("I Ain't Worried", artistOneRepublic, "Top Gun: Maverick"),
        DemoSongSpec("If I Lose Myself", artistOneRepublic, "Native"),
        DemoSongSpec("Someday", artistOneRepublic, "Human"),
        DemoSongSpec("麦门永存", artistMcDonalds, "金拱门精选"),
        DemoSongSpec("Never Gonna Give You Up", artistRickAstley, "Whenever You Need Somebody"),
        DemoSongSpec("忘记了也没关系", artistDouDou, "忘记了也没关系"),
        DemoSongSpec("DAMIDAMI", artistZenless, "绝区零原声带")
    )

    val demoTracks = songSpecs.mapIndexed { index, spec ->
        val remoteId = "track-${index + 1}"
        val albumRef = SourceEntityRef("album-$remoteId", spec.albumName, "album")
        SourceTrackDetails(
            track = SourceTrack(
                identity = TrackIdentity(sourceId, null, remoteId),
                title = spec.title,
                artists = listOf(spec.artist.reference.name)
            ),
            stableId = "$sourceId:$remoteId",
            album = spec.albumName,
            artists = listOf(spec.artist.reference),
            albumRef = albumRef,
            durationMs = 180_000,
            access = TrackAccessInfo(playable = false, reason = "仅用于公共界面演示，不提供音频")
        )
    }

    val collection = SourceCollection(
        identity = TrackIdentity(sourceId, null, "collection-demo"),
        stableId = "$sourceId:collection-demo",
        title = "演示歌单",
        description = "${demoTracks.size} 首虚拟歌曲，用于列表、多选与返回演示",
        count = demoTracks.size
    )

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
        override suspend fun suggestions(query: String) = listOf(
            "RADWIMPS", "Linkin Park", "OneRepublic", "麦门永存", "Never Gonna Give You Up",
            "DOUDOU", "绝区零", "DAMIDAMI", "前前前世", "Numb", "Counting Stars"
        ).filter { it.contains(query, ignoreCase = true) }

        override suspend fun search(query: String, kind: SourceSearchKind, request: SourceCursorRequest): SourceSearchResult {
            return when (kind) {
                SourceSearchKind.TRACK -> page(
                    demoTracks.filter { it.track.title.contains(query, ignoreCase = true) || it.artistsText.contains(query, ignoreCase = true) },
                    request
                ).let { SourceSearchResult(tracks = it.items, nextCursor = it.nextCursor) }
                SourceSearchKind.COLLECTION -> SourceSearchResult(collections = listOf(collection).filter { it.title.contains(query, ignoreCase = true) })
                SourceSearchKind.ARTIST -> SourceSearchResult(entities = allArtists.filter { it.reference.name.contains(query, ignoreCase = true) })
                SourceSearchKind.ALBUM -> SourceSearchResult(entities = listOf(album).filter { it.reference.name.contains(query, ignoreCase = true) })
                SourceSearchKind.CONTENT -> throw UnsupportedOperationException("示例未声明内容搜索")
            }
        }
    }

    override val kinds = setOf("artist", "album")
    override suspend fun detail(collection: SourceCollection) = this.collection
    override suspend fun tracks(collection: SourceCollection, request: SourceCursorRequest) = page(demoTracks, request)
    override suspend fun entity(reference: SourceEntityRef) = when (reference.kind) {
        "artist" -> artistMap[reference.id] ?: artist
        "album" -> if (reference.id == album.reference.id) album else SourceEntity(reference, "${reference.name} 资料")
        else -> throw UnsupportedOperationException("示例未声明此实体类型")
    }
    override suspend fun tracks(reference: SourceEntityRef, request: SourceCursorRequest) = page(
        demoTracks.filter { it.artists.any { a -> a.id == reference.id } || it.albumRef?.id == reference.id }
            .ifEmpty { demoTracks },
        request
    )
    override suspend fun collections(reference: SourceEntityRef, request: SourceCursorRequest) = page(listOf(collection), request)
}
