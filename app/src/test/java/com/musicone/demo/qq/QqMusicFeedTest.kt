package com.musicone.demo

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import androidx.compose.ui.unit.dp
import kotlin.random.Random

class QqMusicFeedTest {
    @Test fun laterPagesKeepOfficialSongShelvesForTheTopSection() {
        val song = feedTrack("qq-song")
        val page = QqMusicFeedPage(
            cards = listOf(
                QqMusicFeedCard.SongShelf("207", "歌曲推荐", listOf(listOf(
                    QqMusicFeedCard.Song(song, "推荐", section = QqMusicFeedSection.SONG_RECOMMENDATION),
                ))),
                QqMusicFeedCard.Song(song.copy(id = "qq-flow"), "音乐流"),
            ),
            shelfIds = emptyList(),
            shelfCount = 0,
            uniqueKeys = emptyList(),
            loadMark = 0,
        )
        assertEquals(2, page.forFeedPage(1).cards.size)
        assertEquals(
            listOf(QqMusicFeedSection.SONG_RECOMMENDATION, QqMusicFeedSection.MUSIC_FLOW),
            page.forFeedPage(2).cards.map { it.section },
        )
    }

    @Test
    fun qqFavoriteIdentityMatchesShortAndDetailedFeedTracks() {
        val shortTrack = feedTrack("qq-123456").copy(catalogId = "123456", songMid = "")
        val detailedTrack = shortTrack.copy(id = "qq-001AbcdEF12345", songMid = "001AbcdEF12345")

        assertTrue(shortTrack.isQqFavorite(setOf("qq-123456")))
        assertTrue(detailedTrack.isQqFavorite(setOf("qq-123456")))
        assertTrue(detailedTrack.isQqFavorite(setOf("qq-001AbcdEF12345")))
        assertFalse(shortTrack.isQqFavorite(setOf("qq-999999")))
    }

    @Test
    fun paginationSendsServerCursorAndStyle() {
        val first = qqMusicFeedRequest(1, 0, emptyList()).getJSONObject("param")
        assertEquals(0, first.getInt("direction"))
        assertEquals(1, first.getInt("page"))
        assertEquals("1", first.getJSONObject("ext").getString("is_20_style"))
        assertEquals("1", first.getJSONObject("ext").getString("has_login"))
        assertEquals("1", first.getJSONObject("ext").getString("no_need_sound_quality"))
        assertTrue(first.getJSONObject("ext").getString("client_time_zone").isNotBlank())
        val next = qqMusicFeedRequest(4, 12, listOf("315"), listOf("1_0_123")).getJSONObject("param")
        assertEquals(1, next.getInt("direction"))
        assertEquals(4, next.getInt("page"))
        assertEquals(12, next.getInt("s_num"))
        assertEquals("315", next.getJSONArray("v_cache").getString(0))
        assertEquals("1_0_123", next.getJSONArray("v_uniq").getString(0))
    }

    @Test
    fun mixedShelfPreservesOrderAndDoesNotTurnUnknownCardsIntoPlaylists() {
        val page = parseQqMusicFeed(fixture("""
            {"type":1,"style":302,"jumptype":10014,"id":"123","title":"A &amp; B","cover":"https://example.com/cover.jpg",
             "v_user":[{"nick":"Rings of Saturn"}]},
            {"type":999,"jumptype":999,"id":"456","title":"未知卡片"},
            {"type":2,"style":301,"jumptype":10014,"id":"single-song-card","title":"听听这些","card_extra_info":{"SongList":[
                {"ID":99,"MID":"001AbcdEF12345","Name":"歌曲","SingerName":"歌手","Cover":"https://example.com/song.jpg"}
            ]}}
        """), false)
        assertEquals(1, page.shelfCount)
        assertEquals(listOf("315"), page.shelfIds)
        assertEquals(2, page.cards.size)
        val playlist = (page.cards[0] as QqMusicFeedCard.Playlist).playlist
        assertEquals("A & B", playlist.title)
        assertEquals("Rings of Saturn", playlist.subtitle)
        val song = (page.cards[1] as QqMusicFeedCard.Song).track
        assertEquals("qq-001AbcdEF12345", song.id)
        assertEquals("99", song.catalogId)
        assertEquals("歌手", song.artists)
        assertEquals("https://example.com/song.jpg", song.artworkUrl)
    }

    @Test
    fun cardLevelSongFieldsAreRecognizedWithoutTrackArray() {
        val page = parseQqMusicFeed(fixture("""
            {"style":301,"jumptype":10014,"id":"73456789","title":"卡片单曲","subtitle":"歌手甲",
             "cover":"https://example.com/card-song.jpg","miscellany":{"rcmd_reason":"因为你常听"}},
            {"style":5105,"jumptype":10002,"id":"waterfall-card","subid":"83456789",
             "title":"瀑布流单曲","subtitle":"歌手乙","scheme":"qqmusic://song?songmid=007AbcdEF12345"}
        """), false)

        val direct = (page.cards[0] as QqMusicFeedCard.Song)
        assertEquals("qq-73456789", direct.track.id)
        assertEquals("73456789", direct.track.catalogId)
        assertEquals("歌手甲", direct.track.artists)
        assertEquals("因为你常听", direct.recommendationTitle)
        val waterfall = (page.cards[1] as QqMusicFeedCard.Song).track
        assertEquals("qq-83456789", waterfall.id)
        assertEquals("83456789", waterfall.catalogId)
        assertEquals("", waterfall.mediaId)
    }

    @Test
    fun liveV20SongCardUsesNumericIdAndDoesNotTrustSubIdAsMid() {
        val page = parseQqMusicFeed(fixture("""
            {"style":208,"type":200,"subtype":0,"jumptype":10046,"id":"325608325",
             "subid":"001AbcdEF12345","title":"Night Voyager","subtitle":"星尘"}
        """), false)

        val song = page.cards.single() as QqMusicFeedCard.Song
        assertEquals("qq-325608325", song.track.id)
        assertEquals("325608325", song.track.catalogId)
        assertEquals("", song.track.mediaId)
        assertEquals("星尘", song.track.artists)
    }

    @Test
    fun aiFolderWithQuotedSeedBecomesResolvableSongInsteadOfPlaylist() {
        val page = parseQqMusicFeed(fixture("""
            {"style":205,"type":500,"subtype":2001,"jumptype":10014,"id":"212324",
             "title":"从《夜航星 (Night Voyager)》中开启今天的晚霞","cover":"https://example.com/night.jpg"}
        """), false)

        val song = page.cards.single() as QqMusicFeedCard.Song
        assertEquals("夜航星 (Night Voyager)", song.track.title)
        assertEquals("夜航星 (Night Voyager)", song.lookupQuery)
        assertEquals("从《夜航星 (Night Voyager)》中开启今天的晚霞", song.recommendationTitle)
    }

    @Test
    fun preferredAlbumPreviewIsNotDisplayedAsThreeSongGroup() {
        val page = parseQqMusicFeed(fixture("""
            {"style":207,"type":400,"subtype":414,"jumptype":10002,"id":"53067251",
             "title":"OneRepublic","subtitle":"Artificial Paradise (Deluxe)","cover":"https://example.com/album.jpg",
             "card_extra_info":{"SongList":[
                {"ID":499831709,"MID":"","Name":"Artificial Paradise","SingerName":""},
                {"ID":499831712,"MID":"","Name":"Hurt","SingerName":""},
                {"ID":499831710,"MID":"","Name":"Sink Or Swim","SingerName":""}
             ]}}
        """), false)

        val song = page.cards.single() as QqMusicFeedCard.Song
        assertEquals("qq-499831709", song.track.id)
        assertEquals("Artificial Paradise (Deluxe)", song.track.album)
    }

    @Test
    fun officialThreeSongGroupRequiresMoreThanOneAlbum() {
        val page = parseQqMusicFeed(fixture("""
            {"style":304,"type":200,"subtype":206,"id":"group-mixed","title":"猜你喜欢的好歌","card_extra_info":{"SongList":[
                {"mid":"001AbcdEF12345","id":1,"name":"歌曲一","singer":[{"name":"歌手一"}],"album":{"mid":"101AbcdEF12345","name":"专辑一"}},
                {"mid":"002AbcdEF12345","id":2,"name":"歌曲二","singer":[{"name":"歌手二"}],"album":{"mid":"102AbcdEF12345","name":"专辑二"}},
                {"mid":"003AbcdEF12345","id":3,"name":"歌曲三","singer":[{"name":"歌手三"}],"album":{"mid":"103AbcdEF12345","name":"专辑三"}}
            ]}}
        """), false)

        val group = page.cards.single() as QqMusicFeedCard.SongGroup
        assertEquals("猜你喜欢的好歌", group.recommendationTitle)
        assertEquals(listOf("专辑一", "专辑二", "专辑三"), group.tracks.map { it.album })
    }

    @Test
    fun sameAlbumSimilarSongCardIsNotPromotedToThreeSongGroup() {
        val page = parseQqMusicFeed(fixture("""
            {"style":304,"type":200,"subtype":206,"id":"group-album","title":"专辑试听","card_extra_info":{"SongList":[
                {"mid":"004AbcdEF12345","id":4,"name":"歌曲一","singer":[{"name":"歌手一"}],"album":{"mid":"104AbcdEF12345","name":"同一张专辑"}},
                {"mid":"005AbcdEF12345","id":5,"name":"歌曲二","singer":[{"name":"歌手一"}],"album":{"mid":"104AbcdEF12345","name":"同一张专辑"}},
                {"mid":"006AbcdEF12345","id":6,"name":"歌曲三","singer":[{"name":"歌手一"}],"album":{"mid":"104AbcdEF12345","name":"同一张专辑"}}
            ]}}
        """), false)

        assertTrue(page.cards.isEmpty())
    }

    @Test
    fun validatedSameAlbumGroupIsNotDisplayed() {
        val tracks = (1..3).map { index ->
            feedTrack("same-$index").copy(album = "同一张专辑", albumMid = "album-mid")
        }
        val cards = normalizeQqMusicFeedSongGroup(
            QqMusicFeedCard.SongGroup("推荐好歌", tracks, listOf("文案一", "文案二", "文案三")),
        )

        assertTrue(cards.isEmpty())
    }

    @Test
    fun officialStylesSeparateSongPlaylistAndThreeSongGroup() {
        val page = parseQqMusicFeed(fixture("""
            {"style":301,"jumptype":10014,"id":"single","title":"因为你常听摇滚","card_extra_info":{"Tracks":[
                {"mid":"001AbcdEF12345","id":99,"name":"单曲","singer":[{"name":"歌手"}]}
            ]}},
            {"style":302,"jumptype":10014,"id":"123","title":"歌单","card_extra_info":{"SongList":[
                {"ID":98,"MID":"002AbcdEF12345","Name":"歌单试听曲","SingerName":"歌手"}
            ]}},
            {"style":304,"id":"group","title":"重温你喜欢的歌","card_extra_info":{"SongList":[
                {"ID":1,"MID":"003AbcdEF12345","Name":"歌曲一","SingerName":"甲","AlbumName":"专辑一","AlbumMID":"101AbcdEF12345"},
                {"ID":2,"MID":"004AbcdEF12345","Name":"歌曲二","SingerName":"乙","AlbumName":"专辑二","AlbumMID":"102AbcdEF12345"},
                {"ID":3,"MID":"005AbcdEF12345","Name":"歌曲三","SingerName":"丙","AlbumName":"专辑三","AlbumMID":"103AbcdEF12345"}
            ]}}
        """), false)

        assertTrue(page.cards[0] is QqMusicFeedCard.Song)
        assertTrue(page.cards[1] is QqMusicFeedCard.Playlist)
        val group = page.cards[2] as QqMusicFeedCard.SongGroup
        assertEquals("重温你喜欢的歌", group.recommendationTitle)
        assertEquals(listOf("歌曲一", "歌曲二", "歌曲三"), group.tracks.map { it.title })
    }

    @Test
    fun similarSongCardWithoutAlbumIdentityIsKeptUntilMetadataValidation() {
        val page = parseQqMusicFeed(fixture("""
            {"style":304,"type":200,"subtype":206,"id":"group-no-album","title":"缺少专辑信息","card_extra_info":{"SongList":[
                {"mid":"007AbcdEF12345","id":7,"name":"歌曲一","singer":[{"name":"歌手一"}]},
                {"mid":"008AbcdEF12345","id":8,"name":"歌曲二","singer":[{"name":"歌手二"}]},
                {"mid":"009AbcdEF12345","id":9,"name":"歌曲三","singer":[{"name":"歌手三"}]}
            ]}}
        """), false)

        assertTrue(page.cards.single() is QqMusicFeedCard.SongGroup)
    }

    @Test
    fun dailyThirtyBusinessCardIsExcludedFromMusicFeed() {
        val page = parseQqMusicFeed(fixture("""
            {"style":202,"type":500,"subtype":510,"jumptype":10014,"id":"9094593995","title":"每日30首"},
            {"style":301,"id":" ambiguous","title":"歌曲名","miscellany":{"rcmd_reason":"最近常听"},
             "card_extra_info":{"Tracks":[{"mid":"006AbcdEF12345","id":6,"name":"歌曲名","singer":[{"name":"歌手"}]}]}}
        """), false)

        assertEquals(1, page.cards.size)
        assertEquals("最近常听", (page.cards.single() as QqMusicFeedCard.Song).recommendationTitle)
    }

    @Test
    fun audiobookCardsAreExcludedFromMusicFeed() {
        val page = parseQqMusicFeed(fixture("""
            {"style":208,"type":1700,"jumptype":20007,"id":"podcast-1","title":"听书节目"},
            {"style":208,"type":400,"subtype":410,"jumptype":10025,"id":"podcast-2","title":"节目歌单"}
        """), false)

        assertTrue(page.cards.isEmpty())
    }

    @Test
    fun feedColumnsKeepPhoneAndLandscapeTabletLayoutsDistinct() {
        assertEquals(2, qqMusicFeedColumns(360.dp, 800.dp))
        assertEquals(2, qqMusicFeedColumns(375.dp, 812.dp))
        assertEquals(3, qqMusicFeedColumns(800.dp, 1_200.dp))
        assertEquals(3, qqMusicFeedColumns(1_280.dp, 800.dp))
        assertEquals(1_180.dp, qqMusicFeedContentMaxWidth(1_280.dp, 800.dp))
    }

    @Test
    fun unsupportedOrMalformedCardsNeverFallBackToPlaylist() {
        val page = parseQqMusicFeed(fixture("""
            {"style":90,"jumptype":10014,"id":"123","title":"专辑不能冒充歌单"},
            {"style":301,"jumptype":10014,"id":"invalid","title":"损坏的单曲",
             "card_extra_info":{"Tracks":[{"mid":""}]}}
        """), false)

        assertTrue(page.cards.isEmpty())
    }

    @Test
    fun malformedSongsAndRepeatedCardsDoNotPolluteFeed() {
        val page = parseQqMusicFeed(fixture("""
            {"style":302,"jumptype":10014,"id":"123","title":"歌单"},
            {"style":302,"jumptype":10014,"id":"123","title":"重复"},
            {"card_extra_info":{"SongList":[{"MID":"","Name":"无效"}]}},
            {"jumptype":10014,"id":"invalid","title":"错误 ID"}
        """), false)
        assertEquals(1, page.cards.size)
        assertEquals(page.cards, mergeQqMusicFeed(page.cards, page.cards))
    }

    @Test
    fun serverBatchKeepsCardsAndTheirOrder() {
        fun track(id: String) = MusicTrack(
            id = id,
            source = MusicSource.QQ,
            title = id,
            artists = "歌手",
            album = "",
            durationMs = 0,
            artworkStart = 0,
            artworkEnd = 0,
            artworkMark = id,
            previewUrl = "",
        )
        fun playlist(id: String) = QqMusicFeedCard.Playlist(MusicPlaylist(
            id = id,
            source = MusicSource.QQ,
            title = id,
            subtitle = "",
            description = "",
            count = 0,
            artworkStart = 0,
            artworkEnd = 0,
            artworkMark = id,
            tracks = emptyList(),
        ))
        val cards = listOf(
            QqMusicFeedCard.Song(track("s1"), "推荐一"),
            QqMusicFeedCard.Song(track("s2"), "推荐二"),
            QqMusicFeedCard.Song(track("s3"), "推荐三"),
            QqMusicFeedCard.Song(track("s4"), "推荐四"),
            playlist("p1"),
            playlist("p2"),
            QqMusicFeedCard.SongGroup("歌曲组", listOf(track("g1s1"))),
        )

        val batch = takeQqMusicFeedBatch(cards, limit = 4)

        assertEquals(4, batch.visible.size)
        assertEquals(listOf("song:s1", "song:s2", "song:s3", "song:s4"), batch.visible.map { it.key })
        assertEquals(3, batch.remaining.size)
        assertEquals(cards.distinctBy { it.key }.map { it.key }, (batch.visible + batch.remaining).map { it.key })
    }

    @Test
    fun playlistPoolIsNotArtificiallyCapped() {
        val cards = parseQqMusicFeed(fixture((1..20).joinToString(",") {
            """{"style":302,"jumptype":10014,"id":"$it","title":"歌单$it"}"""
        }), false).cards

        val batch = takeQqMusicFeedBatch(cards, 8)

        assertEquals(8, batch.visible.size)
        assertTrue(batch.visible.all { it is QqMusicFeedCard.Playlist })
        assertEquals(20, (batch.visible + batch.remaining).size)
    }

    @Test
    fun availableCardsInterleaveSongsAndPlaylists() {
        fun playlist(id: String) = QqMusicFeedCard.Playlist(MusicPlaylist(
            id = id,
            source = MusicSource.QQ,
            title = id,
            subtitle = "",
            description = "",
            count = 0,
            artworkStart = 0,
            artworkEnd = 0,
            artworkMark = id,
            tracks = emptyList(),
        ))
        fun shelf(id: String) = QqMusicFeedCard.SongShelf(
            shelfId = id,
            title = id,
            pages = listOf(listOf(QqMusicFeedCard.Song(feedTrack("$id-track"), ""))),
        )
        val cards = listOf(
            shelf("shelf-1"),
            QqMusicFeedCard.Song(feedTrack("detail-1"), "详细歌曲一"),
            playlist("playlist-1"),
            QqMusicFeedCard.SongGroup("歌曲组", listOf(feedTrack("group-1"))),
            shelf("shelf-2"),
            playlist("playlist-2"),
            QqMusicFeedCard.Song(feedTrack("detail-2"), "详细歌曲二"),
            shelf("shelf-3"),
        )

        val shuffled = interleaveQqMusicFeedCards(cards, Random(42))

        assertEquals(cards.map(QqMusicFeedCard::key).toSet(), shuffled.map(QqMusicFeedCard::key).toSet())
        assertEquals(
            listOf(cards[0].key, cards[3].key, cards[4].key, cards[7].key),
            listOf(shuffled[0].key, shuffled[3].key, shuffled[4].key, shuffled[7].key),
        )
        assertEquals(
            cards.filter { it is QqMusicFeedCard.Song || it is QqMusicFeedCard.Playlist }.map(QqMusicFeedCard::key).toSet(),
            shuffled.filter { it is QqMusicFeedCard.Song || it is QqMusicFeedCard.Playlist }
                .map(QqMusicFeedCard::key).toSet(),
        )
        val candidateCards = shuffled.filter {
            it is QqMusicFeedCard.Song || it is QqMusicFeedCard.Playlist
        }
        assertTrue(candidateCards.zipWithNext().all { (left, right) ->
            (left is QqMusicFeedCard.Song) != (right is QqMusicFeedCard.Song)
        })
        assertEquals(
            cards.filter { it is QqMusicFeedCard.SongShelf || it is QqMusicFeedCard.SongGroup }.map(QqMusicFeedCard::key),
            shuffled.filter { it is QqMusicFeedCard.SongShelf || it is QqMusicFeedCard.SongGroup }
                .map(QqMusicFeedCard::key),
        )
    }

    @Test
    fun fullTrackKeepsPlaybackMetadata() {
        val page = parseQqMusicFeed(fixture("""
            {"card_extra_info":{"Tracks":[{"mid":"001AbcdEF12345","id":99,"name":"完整歌曲",
            "singer":[{"name":"创作者"}],"album":{"mid":"002AbcdEF12345","name":"专辑"},
            "interval":180,"file":{"media_mid":"003AbcdEF12345","size_128mp3":1234}}]}}
        """), false)
        val track = (page.cards.single() as QqMusicFeedCard.Song).track
        assertEquals(180000L, track.durationMs)
        assertEquals("003AbcdEF12345", track.mediaId)
        assertEquals("专辑", track.album)
    }

    @Test(expected = PlatformApiException::class)
    fun missingShelfStructureIsAnErrorRatherThanAnEmptySuccess() {
        parseQqMusicFeed(JSONObject("{}"), false)
    }

    @Test
    fun emptyShelfIsRecognizedAndAlternateEnvelopeIsSupported() {
        assertEquals(0, parseQqMusicFeed(JSONObject("""{"v_shelf":[]}"""), false).shelfCount)
        assertEquals(1, parseQqMusicFeed(JSONObject("""{"Shelfs":[{"id":315,"v_niche":[]}]}"""), false).shelfCount)
        assertEquals(7, parseQqMusicFeed(JSONObject("""{"load_mark":7,"v_shelf":[]}"""), false).loadMark)
    }

    @Test
    fun uniqueKeysOnlyComeFromOfficialUniqueShelf() {
        val page = parseQqMusicFeed(JSONObject("""{"v_shelf":[
            {"id":207,"v_niche":[{"v_card":[{"type":200,"subtype":0,"id":"1"}]}]},
            {"id":315,"v_niche":[{"v_card":[{"type":200,"subtype":0,"id":"2"}]}]}
        ]}"""), false)
        assertEquals(listOf("200_0_2"), page.uniqueKeys)
    }

    @Test fun recommendationUsesOnlyOfficialTitleLabelAndRemovesNavigationDecoration() {
        val card = JSONObject("""{"tags":[
            {"Tag":"评论999+","Exts":{}},
            {"Tag":"NME获奖","Exts":{"r_source":"recommend"}},
            {"Tag":"“吉尼斯认证的破纪录神曲” >","Exts":{"TitleLabel":"1"}}
        ],"miscellany":{"rcmd_reason":"为你推荐"}}""")
        assertEquals("吉尼斯认证的破纪录神曲", card.qqMusicFeedRecommendationTitle("As It Was", "As It Was"))
        assertEquals("", JSONObject("""{"tags":[{"Tag":"喜欢15w+"}]}""")
            .qqMusicFeedRecommendationTitle("As It Was", "As It Was"))
        assertEquals("因为常听《夜航星》", cleanQqFeedRecommendation("因为常听《夜航星》"))
    }

    @Test
    fun officialThreeRowShelfKeepsTitleAndServerPageOrder() {
        val page = parseQqMusicFeed(JSONObject("""
            {"v_shelf":[{"id":"207","title_template":"「{String}」，这是你的宝藏好歌💎","title_content":"ShuyunR",
            "v_niche":[
                {"style":10001,"v_card":[
                    {"style":208,"type":200,"id":"1","title":"第一首","subtitle":"甲"},
                    {"style":208,"type":200,"id":"2","title":"第二首","subtitle":"乙"},
                    {"style":208,"type":200,"id":"3","title":"第三首","subtitle":"丙"}]},
                {"style":10001,"v_card":[
                    {"style":208,"type":200,"id":"4","title":"第四首","subtitle":"丁"},
                    {"style":208,"type":200,"id":"5","title":"第五首","subtitle":"戊"},
                    {"style":208,"type":200,"id":"6","title":"第六首","subtitle":"己"}]}
            ]}]}
        """), false)

        val shelf = page.cards.single() as QqMusicFeedCard.SongShelf
        assertEquals(QqMusicFeedSection.SONG_RECOMMENDATION, shelf.section)
        assertEquals("「ShuyunR」，这是你的宝藏好歌💎", shelf.title)
        assertEquals(listOf("第一首", "第二首", "第三首"), shelf.pages[0].map { it.track.title })
        assertEquals(listOf("第四首", "第五首", "第六首"), shelf.pages[1].map { it.track.title })
    }

    @Test
    fun officialMusicFlowKeepsSingleGroupAndPlaylistCardInterleaving() {
        val page = parseQqMusicFeed(JSONObject("""
            {"v_shelf":[{"id":"315","v_niche":[{"style":10004,"v_card":[
                {"style":301,"type":200,"id":"single-1","title":"单曲一","card_extra_info":{"Tracks":[
                    {"mid":"001AbcdEF12345","id":1,"name":"单曲一","singer":[{"name":"歌手一"}]}
                ]}},
                {"style":304,"type":200,"subtype":206,"id":"group-1","title":"三行歌曲","card_extra_info":{"SongList":[
                    {"ID":2,"MID":"002AbcdEF12345","Name":"歌曲二","SingerName":"歌手二","AlbumName":"专辑二","AlbumMID":"102AbcdEF12345"},
                    {"ID":3,"MID":"003AbcdEF12345","Name":"歌曲三","SingerName":"歌手三","AlbumName":"专辑三","AlbumMID":"103AbcdEF12345"},
                    {"ID":4,"MID":"004AbcdEF12345","Name":"歌曲四","SingerName":"歌手四","AlbumName":"专辑四","AlbumMID":"104AbcdEF12345"}
                ]}},
                {"style":301,"type":200,"id":"single-5","title":"单曲五","card_extra_info":{"Tracks":[
                    {"mid":"005AbcdEF12345","id":5,"name":"单曲五","singer":[{"name":"歌手五"}]}
                ]}},
                {"style":302,"type":500,"subtype":2001,"jumptype":10014,"id":"123456","title":"官方歌单"}
            ]}]}]}
        """), false)

        assertEquals(
            listOf(
                QqMusicFeedCard.Song::class,
                QqMusicFeedCard.SongGroup::class,
                QqMusicFeedCard.Song::class,
                QqMusicFeedCard.Playlist::class,
            ),
            page.cards.map { it::class },
        )
        assertEquals("单曲一", (page.cards[0] as QqMusicFeedCard.Song).track.title)
        assertTrue(page.cards.all { it.section == QqMusicFeedSection.MUSIC_FLOW })
        assertEquals("三行歌曲", (page.cards[1] as QqMusicFeedCard.SongGroup).recommendationTitle)
        assertEquals("单曲五", (page.cards[2] as QqMusicFeedCard.Song).track.title)
        assertEquals("官方歌单", (page.cards[3] as QqMusicFeedCard.Playlist).playlist.title)
    }

    @Test
    fun musicFlowKeepsServerCardOrder() {
        fun song(id: String) = QqMusicFeedCard.Song(feedTrack(id), "")
        fun playlist(id: String) = QqMusicFeedCard.Playlist(MusicPlaylist(
            id = "qq-$id",
            source = MusicSource.QQ,
            title = id,
            subtitle = "",
            description = "",
            count = 0,
            artworkStart = 0,
            artworkEnd = 0,
            artworkMark = id,
            tracks = emptyList(),
        ))
        fun group(id: String) = QqMusicFeedCard.SongGroup(id, listOf(feedTrack("qq-$id")))

        val mixed = normalizeQqMusicFlowCards(listOf(
            song("song-1"), song("song-2"), song("song-3"),
            playlist("playlist-1"), playlist("playlist-2"), playlist("playlist-3"),
            group("group-1"), group("group-2"), group("group-3"),
        ))

        assertEquals(
            listOf("song:song-1", "song:song-2", "song:song-3",
                "playlist:qq-playlist-1", "playlist:qq-playlist-2", "playlist:qq-playlist-3",
                "song-group:qq-group-1", "song-group:qq-group-2", "song-group:qq-group-3"),
            mixed.map(QqMusicFeedCard::key),
        )
    }

    @Test
    fun duplicatePlaylistCardsAreRemovedWithoutChangingOtherCardOrder() {
        fun song(id: String) = QqMusicFeedCard.Song(feedTrack(id), "")
        fun playlist(id: String) = QqMusicFeedCard.Playlist(MusicPlaylist(
            id = "qq-$id",
            source = MusicSource.QQ,
            title = id,
            subtitle = "",
            description = "",
            count = 0,
            artworkStart = 0,
            artworkEnd = 0,
            artworkMark = id,
            tracks = emptyList(),
        ))

        val mixed = normalizeQqMusicFlowCards(listOf(
            song("song-1"), song("song-2"),
            playlist("playlist-1"), playlist("playlist-1"), playlist("playlist-2"),
        ))

        assertEquals(
            listOf("song:song-1", "song:song-2", "playlist:qq-playlist-1", "playlist:qq-playlist-2"),
            mixed.map(QqMusicFeedCard::key),
        )
    }

    @Test
    fun songRecommendationAndMusicFlowRemainSeparateSections() {
        val page = parseQqMusicFeed(JSONObject("""
            {"v_shelf":[
                {"id":"207","title_template":"这是你的惊喜好歌","v_niche":[{"style":10001,"v_card":[
                    {"style":208,"type":200,"id":"1","title":"推荐歌曲","subtitle":"歌手"}
                ]}]},
                {"id":"315","title_template":"你的专属乐流","v_niche":[{"style":10004,"v_card":[
                    {"style":301,"type":200,"id":"song-2","title":"信息流歌曲","card_extra_info":{"Tracks":[
                        {"mid":"002AbcdEF12345","id":2,"name":"信息流歌曲","singer":[{"name":"歌手"}]}
                    ]}},
                    {"style":302,"type":500,"jumptype":10014,"id":"123456","title":"信息流歌单"}
                ]}]}
            ]}
        """), false)

        assertEquals(
            listOf(
                QqMusicFeedSection.SONG_RECOMMENDATION,
                QqMusicFeedSection.MUSIC_FLOW,
                QqMusicFeedSection.MUSIC_FLOW,
            ),
            page.cards.map { it.section },
        )
        assertTrue(page.cards[0] is QqMusicFeedCard.SongShelf)
        assertTrue(page.cards[1] is QqMusicFeedCard.Song)
        assertTrue(page.cards[2] is QqMusicFeedCard.Playlist)
    }

    @Test
    fun listeningProgramAndVipSongSectionsAreRemovedFromMusicFeed() {
        val page = parseQqMusicFeed(JSONObject("""
            {"v_shelf":[
                {"id":"272","title_template":"今日专属精彩节目","v_niche":[{"style":10001,"v_card":[
                    {"style":208,"type":1700,"id":"program-1","title":"听书节目","subtitle":"节目作者"}
                ]}]},
                {"id":"315","title_template":"VIP专属歌曲推荐","v_niche":[{"style":10001,"v_card":[
                    {"style":208,"type":200,"id":"vip-1","title":"VIP歌曲","subtitle":"歌手"}
                ]}]}
            ]}
        """), true)

        assertTrue(page.cards.isEmpty())
    }

    @Test
    fun playlistRecommendationCopyIsKeptWhileStatisticsAreIgnored() {
        val page = parseQqMusicFeed(fixture("""
            {"style":302,"type":500,"jumptype":10014,"id":"123","title":"霓虹唱片行",
             "miscellany":{"p_Template_setTitle":"携手「HAG」进入霓虹唱片行。","fav_cnt":"12w+","cnt_content":"99万 播放"}}
        """), false)
        val playlist = page.cards.single() as QqMusicFeedCard.Playlist
        assertEquals("携手「HAG」进入霓虹唱片行。", playlist.playlist.subtitle)
        assertFalse(playlist.playlist.subtitle.contains("播放"))
    }

    private fun fixture(cards: String) = JSONObject("""{"v_shelf":[{"id":315,"v_niche":[{"v_card":[$cards]}]}]}""")

    private fun feedTrack(id: String) = MusicTrack(
        id = id,
        source = MusicSource.QQ,
        title = id,
        artists = "歌手",
        album = "专辑",
        durationMs = 180_000,
        artworkStart = 0,
        artworkEnd = 0,
        artworkMark = "歌",
        previewUrl = "",
        catalogId = "1",
        mediaId = id.removePrefix("qq-"),
    )
}
