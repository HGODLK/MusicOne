package com.musicone.source.kugou

import com.musicone.source.sdk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KugouContentProtocolTest {
    @Test fun artistAlbumsUsePublishTimeOrder() {
        // 歌手页专辑默认从新到旧：sort=1，热度序 3 不能出现在该请求里。
        val request = KugouContentClient().artistAlbumsRequest("3520", 2, 30)
        assertEquals("/kmr/v1/author/albums", request.path)
        assertEquals("36", request.tid)
        val body = requireNotNull(request.body)
        assertEquals("3520", body.getString("author_id"))
        assertEquals(2, body.getInt("page"))
        assertEquals(30, body.getInt("pagesize"))
        assertEquals(1, body.getInt("sort"))
        assertEquals(1, body.getInt("category"))
        assertEquals("all", body.getString("area_code"))
    }

    @Test fun onlyExplicitAnonymousReadsOmitSessionAndAlbumReadsKeepAccountContext() = runBlocking {
        val forwarded = mutableListOf<String>()
        val client = KugouContentClient { _, cookie ->
            forwarded += cookie
            JSONObject("""{"status":1,"data":[]}""")
        }
        client.load(client.albumDetailRequest("1773341"), "test-account-session")
        client.load(client.albumTracksRequest("1773341", 1, 50), "test-account-session")
        client.load(client.searchRequest("radwimps", SourceSearchKind.TRACK, 1, 50), "test-account-session")
        client.load(KugouContentRequest("/public-photo-seed", publicCatalog = true), "test-account-session")
        assertEquals(listOf("test-account-session", "test-account-session", "test-account-session", ""), forwarded)
    }

    @Test fun hostHundredTrackRequestUsesValidAlbumPagesWithoutDroppingRemainingSongs() {
        val scope = "entity:album:1773341:tracks"
        val first = KugouContentPage.from(SourceCursorRequest(limit = 100), scope, "account", KUGOU_ALBUM_PAGE_SIZE)
        assertEquals(50, first.size)
        assertEquals(50, KugouContentClient().albumTracksRequest("1773341", first.page, first.size).body!!.getInt("pagesize"))
        val response = JSONObject().put("data", JSONObject().put("total", 77))
        val rows = (1..50).map { JSONObject().put("audio_id", it) }
        val cursor = requireNotNull(first.next(response, rows))
        val second = KugouContentPage.from(SourceCursorRequest(cursor, 100), scope, "account", KUGOU_ALBUM_PAGE_SIZE)
        assertEquals(2, second.page)
        assertEquals(50, second.size)
        assertEquals(50L, second.received)
        assertNull(second.next(response, (51..77).map { JSONObject().put("audio_id", it) }))
        assertThrows(IllegalArgumentException::class.java) { KugouContentClient().albumTracksRequest("1773341", 1, 51) }
    }

    @Test fun realAlbumSchemaTakesMissingResourceIdsFromBaseWithoutChangingHashQualities() {
        val raw = JSONObject("""{"base":{"album_id":1773341,"album_audio_id":39886699,"audio_id":22532888,
            "audio_name":"夢灯籠 (梦灯笼)","author_name":"RADWIMPS"},
            "audio_info":{"hash":"6D3C6975D836B8CA54F5A3DF0604133E","hash_128":"6D3C6975D836B8CA54F5A3DF0604133E",
            "hash_320":"6267DA88DFEF68130408226DD3DD9557","hash_flac":"D18B6D53D73C9FA09307417648341BEF",
            "duration":128391,"duration_128":128391},
            "album_info":{"album_name":"君の名は。","cover":"https://cdn.kugou.com/album.jpg"}}""")
        val track = raw.toKugouContentTrack(false)
        assertEquals("kugou-6D3C6975D836B8CA54F5A3DF0604133E", track.id)
        assertEquals("39886699", track.catalogId)
        assertEquals("1773341", track.mediaId)
        assertEquals("1773341", track.albumReference?.id)
        assertEquals(raw.getJSONObject("audio_info").toKugouTrack(false).qualityIds, track.qualityIds)
        assertEquals(128391L, track.durationMs)
        assertFalse(raw.has("album_audio_id"))
        val audioOnly = JSONObject(raw.toString())
        audioOnly.getJSONObject("base").remove("album_audio_id")
        assertEquals("22532888", audioOnly.toKugouContentTrack(false).catalogId)
    }

    @Test fun existingTopLevelResourceIdAliasesWinOverAlbumBase() {
        val raw = JSONObject("""{"MixSongID":111,"AlbumID":222,
            "base":{"album_id":1773341,"album_audio_id":39886699,"audio_id":22532888},
            "audio_info":{"hash":"existing-hash"}}""")
        val track = raw.toKugouContentTrack(false)
        assertEquals("kugou-existing-hash", track.id)
        assertEquals("111", track.catalogId)
        assertEquals("222", track.mediaId)
        assertEquals("222", track.albumReference?.id)
        raw.remove("MixSongID")
        raw.put("audio_id", 333)
        assertEquals("333", raw.toKugouContentTrack(false).catalogId)
    }

    @Test fun albumRequestsUseDocumentedIdsBodiesRouterAndPaging() {
        val client = KugouContentClient()
        val detail = client.albumDetailRequest("123456")
        assertEquals("/kmr/v2/albums", detail.path)
        assertEquals("openapi.kugou.com", detail.router)
        assertEquals("255", detail.tid)
        assertEquals("123456", detail.body!!.getJSONArray("data").getJSONObject(0).getString("album_id"))
        assertEquals(0, detail.body.getInt("is_buy"))
        assertTrue(detail.body.getString("fields").split(',').containsAll(listOf("album_id", "album_name", "intro", "authors")))
        val tracks = client.albumTracksRequest("123456", 3, 50)
        assertEquals("/v1/album_audio/lite", tracks.path)
        assertEquals("123456", tracks.body!!.getString("album_id"))
        assertEquals(3, tracks.body.getInt("page"))
        assertEquals(50, tracks.body.getInt("pagesize"))
        assertEquals("", tracks.body.getString("is_buy"))
        assertThrows(IllegalArgumentException::class.java) { client.albumDetailRequest("000") }
        assertThrows(IllegalArgumentException::class.java) { client.albumTracksRequest("album:123456", 1, 50) }
        assertThrows(IllegalArgumentException::class.java) { client.albumTracksRequest("123456", 0, 50) }
    }

    @Test fun albumDetailSelectsRequestedIdAndPreservesIntroduction() {
        val response = JSONObject("""{"status":1,"error_code":0,"data":[
            {"album_id":999,"album_name":"其他专辑"},
            {"album_id":123456,"album_name":"目标专辑","intro":"完整专辑介绍"}]}""")
        val entity = response.kugouContentAlbumEntity("123456")
        assertEquals("123456", entity.reference.id)
        assertEquals("目标专辑", entity.reference.name)
        assertEquals("完整专辑介绍", entity.description)
        assertThrows(PlatformApiException::class.java) { response.kugouContentAlbumEntity("111") }
        assertThrows(PlatformApiException::class.java) {
            JSONObject("""{"status":1,"data":[]}""").kugouContentAlbumEntity("123456")
        }
        assertThrows(PlatformApiException::class.java) {
            JSONObject("""{"status":0,"error_code":20010,"data":[]}""").kugouContentAlbumEntity("123456")
        }
    }

    @Test fun searchAlbumUsesActualAlbumIdAndPublicCollectionMetadata() {
        val intro = "完整专辑简介。".repeat(80)
        val raw = JSONObject().put("albumid", "15319367").put("id", "999")
            .put("albumname", "君の名は。").put("singers", org.json.JSONArray()
                .put(JSONObject().put("id", 1).put("name", "RADWIMPS")))
            .put("publish_time", "2016-08-24").put("songcount", 27)
            .put("img", "https://cdn.kugou.com/240/album.jpg").put("intro", intro)
        val album = raw.toKugouSearchAlbumCollection("account-a")
        assertEquals("kugou-album:15319367", album.stableId)
        assertEquals("album:15319367", album.identity.remoteId)
        assertEquals("RADWIMPS · 2016-08-24 · 27首", album.subtitle)
        assertEquals("https://cdn.kugou.com/240/album.jpg", album.artwork.url)
        assertEquals(intro, album.kugouAlbumDetailDescription())
        assertEquals("album:15319367", KugouSourceMapping.collection(album).remoteId())
        assertEquals("kugou.collection", album.payload.format)
        assertFalse(album.payload.encoded.contains("templateEntity"))
    }

    @Test fun searchAlbumRejectsMissingOrInvalidPlatformAlbumId() {
        listOf("""{"id":123,"albumname":"不是专辑编号"}""", """{"albumid":0}""",
            """{"albumid":"not-album"}""").forEach { input ->
            assertThrows(PlatformApiException::class.java) { JSONObject(input).toKugouSearchAlbumCollection(null) }
        }
        val bare = JSONObject("""{"albumid":123,"albumname":"资料暂缺"}""").toKugouSearchAlbumCollection(null)
        assertEquals("", bare.subtitle)
        assertNull(bare.artwork.url)
        assertNull(bare.count)
    }

    @Test fun searchUsesIndependentPlatformKindsAndRealPages() {
        val client = KugouContentClient()
        val song = client.searchRequest("周杰伦", SourceSearchKind.TRACK, 3, 50)
        assertEquals("/v2/search/song", song.path)
        assertEquals("complexsearch.kugou.com", song.router)
        assertEquals("3", song.query["page"])
        assertEquals("50", song.query["pagesize"])
        assertEquals("0", song.query["privilegefilter"])
        assertEquals("/v1/search/author", client.searchRequest("周杰伦", SourceSearchKind.ARTIST, 2, 30).path)
        assertEquals("/v1/search/album", client.searchRequest("七里香", SourceSearchKind.ALBUM, 1, 20).path)
        assertEquals("/v1/search/special", client.searchRequest("华语", SourceSearchKind.COLLECTION, 1, 30).path)
    }

    @Test fun metadataEnrichmentNeverChangesExistingTrackOrPlaybackIdentity() {
        val item = JSONObject("""{"FileHash":"base","HQFileHash":"high","MixSongID":123,"AlbumID":456,
            "SongName":"歌","SingerName":"甲、乙","AlbumName":"专辑","Duration":180,
            "Singers":[{"id":1,"name":"甲"},{"id":2,"name":"乙"}]}""")
        val original = item.toKugouTrack(false)
        val enriched = item.toKugouContentTrack(false)
        assertEquals(original, enriched)
        val sdk = KugouSourceMapping.track(enriched, "account-a")
        assertEquals(original.id, sdk.stableId)
        assertEquals(original.remoteId(), sdk.track.identity.remoteId)
        assertEquals(listOf("1", "2"), sdk.artists.map { it.id })
        assertEquals("456", sdk.albumRef?.id)
        val restored = KugouSourceMapping.track(sdk)
        assertEquals(original.catalogId, restored.catalogId)
        assertEquals(original.mediaId, restored.mediaId)
        assertEquals(original.qualityIds, restored.qualityIds)
        assertEquals(enriched.artistReferences, restored.artistReferences)
        assertEquals(enriched.albumReference, restored.albumReference)
    }

    @Test fun artistAudioNameKeepsHashQualityAndPlaybackIds() {
        val raw = JSONObject("""{"hash":"adele-hash","hash_320":"adele-hq","audio_name":"Hello",
            "author_name":"Adele","album_audio_id":123,"album_id":456,"duration":295}""")
        val existing = raw.toKugouTrack(false)
        val track = raw.toKugouContentTrack(false)
        assertEquals("Hello", track.title)
        assertEquals("Adele", track.artists)
        assertEquals(existing.id, track.id)
        assertEquals(existing.catalogId, track.catalogId)
        assertEquals(existing.mediaId, track.mediaId)
        assertEquals(existing.qualityIds, track.qualityIds)
        assertEquals(existing.durationMs, track.durationMs)
        assertFalse(raw.has("song_name"))
    }

    @Test fun albumBaseNameAndAuthorAreSeparateFromAudioInfo() {
        val raw = JSONObject("""{"base":{"audio_name":"Someone Like You","author_name":"Adele"},
            "audio_info":{"hash":"album-hash","hash_320":"album-hq","album_audio_id":321,"duration":285},
            "album_info":{"album_id":654,"album_name":"21"}}""")
        val track = raw.toKugouContentTrack(false)
        assertEquals("Someone Like You", track.title)
        assertEquals("Adele", track.artists)
        assertEquals("21", track.album)
        assertEquals("kugou-album-hash", track.id)
        assertEquals("321", track.catalogId)
        assertEquals("654", track.mediaId)
        assertEquals(285_000L, track.durationMs)
        assertEquals("654", track.albumReference?.id)
        assertEquals(raw.optJSONObject("audio_info")!!.toKugouTrack(false).qualityIds, track.qualityIds)
    }

    @Test fun explicitSongNameWinsOverContentAliases() {
        val raw = JSONObject("""{"hash":"existing-hash","SongName":"原名称","SingerName":"原歌手",
            "audio_name":"别名","base":{"audio_name":"专辑别名","author_name":"其他歌手"}}""")
        val track = raw.toKugouContentTrack(false)
        assertEquals("原名称", track.title)
        assertEquals("原歌手", track.artists)
    }

    @Test fun normalEmptyListAndMalformedPayloadAreDifferent() {
        assertTrue(JSONObject("""{"data":{"lists":[]}}""").contentRows("lists").isEmpty())
        assertThrows(PlatformApiException::class.java) { JSONObject("""{"data":{}}""").contentRows("lists") }
        assertThrows(PlatformApiException::class.java) { JSONObject("""{"data":{"lists":[null]}}""").contentRows("lists") }
    }

    @Test fun explicitFailureAndMissingDataCannotBecomeEmptySuccess() {
        assertThrows(PlatformApiException::class.java) { requireKugouContentSuccess(JSONObject("""{"status":0,"error_code":0,"data":{}}""")) }
        assertThrows(PlatformApiException::class.java) { requireKugouContentSuccess(JSONObject("""{"status":1,"error_code":152,"data":{}}""")) }
        assertThrows(PlatformApiException::class.java) { requireKugouContentSuccess(JSONObject("""{"status":1}""")) }
    }

    @Test fun paginationUsesDeclaredTotalEvenWhenPageIsShort() {
        val page = KugouContentPage.from(SourceCursorRequest(limit = 2), "search:artist:甲", "owner-a")
        val rows = listOf(JSONObject("""{"AuthorId":1}"""))
        val next = page.next(JSONObject("""{"data":{"total":3}}"""), rows)!!
        val second = KugouContentPage.from(SourceCursorRequest(next, 2), page.scope, page.owner)
        assertEquals(2, second.page)
        val third = second.next(JSONObject("""{"data":{"total":3}}"""), listOf(JSONObject("""{"AuthorId":2}""")))!!
        assertNull(KugouContentPage.from(SourceCursorRequest(third, 2), page.scope, page.owner)
            .next(JSONObject("""{"data":{"total":3}}"""), listOf(JSONObject("""{"AuthorId":3}"""))))
    }

    @Test fun cursorRejectsAccountQueryAndPageSizeChanges() {
        val page = KugouContentPage.from(SourceCursorRequest(limit = 1), "search:artist:甲", "owner-a")
        val next = page.next(JSONObject("""{"data":{"total":3}}"""), listOf(JSONObject("""{"AuthorId":1}""")))!!
        assertThrows(IllegalArgumentException::class.java) { KugouContentPage.from(SourceCursorRequest(next, 1), page.scope, "owner-b") }
        assertThrows(IllegalArgumentException::class.java) { KugouContentPage.from(SourceCursorRequest(next, 1), "search:artist:乙", page.owner) }
        assertThrows(IllegalArgumentException::class.java) { KugouContentPage.from(SourceCursorRequest(next, 2), page.scope, page.owner) }
    }

    @Test fun missingRemainingRowsAndRepeatedPageThrowInsteadOfEnding() {
        val page = KugouContentPage.from(SourceCursorRequest(limit = 1), "artist:1", "owner-a")
        assertThrows(PlatformApiException::class.java) { page.next(JSONObject("""{"data":{"total":2}}"""), emptyList()) }
        val rows = listOf(JSONObject("""{"audio_id":1}"""))
        val next = page.next(JSONObject("""{"data":{"total":2}}"""), rows)!!
        val second = KugouContentPage.from(SourceCursorRequest(next, 1), page.scope, page.owner)
        assertThrows(PlatformApiException::class.java) { second.next(JSONObject("""{"data":{"total":2}}"""), rows) }
    }

    @Test fun collectionsKeepStableIdentityAndRealFavoriteCoordinates() {
        val item = JSONObject("""{"SpecialID":19,"SpecialName":"测试歌单","SongCount":5,
            "global_collection_id":"global-19","list_create_userid":"7","list_create_listid":"8"}""")
        val result = item.toKugouContentCollection("account-a", false)
        assertEquals("kugou-19", result.stableId)
        assertEquals("19", result.identity.remoteId)
        assertEquals("account-a", result.identity.accountId)
        val payload = JSONObject(result.payload.encoded)
        assertEquals("global-19", payload.getString("global_collection_id"))
        assertEquals("7", payload.getString("list_create_userid"))
        assertEquals("8", payload.getString("list_create_listid"))
        val without = JSONObject("""{"SpecialID":20,"SpecialName":"普通歌单"}""").toKugouContentCollection(null, false)
        assertFalse(JSONObject(without.payload.encoded).has("global_collection_id"))
    }

    @Test fun suggestionGroupsAreCleanedAndDeduplicated() {
        val value = JSONObject("""{"data":[{"RecordDatas":[{"HintInfo":"<em>周杰伦</em>"},{"HintInfo":"周杰伦"}]},
            {"RecordDatas":[{"HintInfo":"周杰伦 七里香"}]}]}""")
        assertEquals(listOf("周杰伦", "周杰伦 七里香"), value.kugouContentSuggestions())
    }

    @Test fun artistAlbumCollectionKeepsExistingPublicAlbumRoute() {
        val album = JSONObject("""{"album_id":456,"album_name":"专辑","song_count":8}""")
            .toKugouContentCollection(null, true)
        assertEquals("kugou-album:456", album.stableId)
        assertEquals("album:456", album.identity.remoteId)
        assertEquals("album:456", KugouSourceMapping.collection(album).remoteId())
        assertEquals("kugou.collection", album.payload.format)
        assertFalse(album.payload.encoded.contains("templateEntity"))
        assertThrows(PlatformApiException::class.java) {
            JSONObject("""{"specialname":"缺少任何编号"}""").toKugouContentCollection(null, false)
        }
    }

    @Test fun artistAlbumRowUsesMetadataButDetailRetainsEntireIntroduction() {
        val intro = "完整专辑介绍。".repeat(150)
        val row = JSONObject().put("album_id", 456).put("album_name", "21").put("author_name", "Adele")
            .put("publish_date", "2011-01-24").put("song_count", 12).put("intro", intro)
        val album = row.toKugouArtistAlbumCollection("account-a")
        assertEquals("Adele · 2011-01-24 · 12首", album.description)
        assertFalse(album.description.contains(intro))
        assertEquals(intro, album.kugouAlbumDetailDescription())
        assertEquals("kugou-album:456", album.stableId)
        assertEquals("album:456", album.identity.remoteId)
        assertEquals(row.toKugouContentCollection("account-a", true).description, album.kugouAlbumDetailDescription())
        val ordinary = JSONObject("""{"SpecialID":19,"specialname":"歌单","intro":"歌单原简介"}""")
            .toKugouContentCollection("account-a", false)
        assertEquals("歌单原简介", ordinary.kugouAlbumDetailDescription())
        assertEquals("歌单原简介", ordinary.description)
    }

    @Test fun globalOnlySearchPlaylistUsesExplicitPublicRouteEvenForNumericGid() {
        val row = JSONObject("""{"gid":"123456","specialname":"Adele精选","nickname":"用户",
            "song_count":12,"img":"https://cdn.kugou.com/150/test.jpg"}""")
        val collection = row.toKugouContentCollection("account-a", false)
        assertEquals("kugou-global:123456", collection.stableId)
        assertEquals("global:123456", collection.identity.remoteId)
        assertEquals("123456", collection.kugouGlobalCollectionId())
        assertEquals("Adele精选", collection.title)
        assertEquals("用户", collection.subtitle)
        assertEquals(12, collection.count)
        assertEquals("https://cdn.kugou.com/150/test.jpg", collection.artwork.url)
        assertEquals("global", JSONObject(collection.payload.encoded).getString("collection_route"))
        assertEquals("123456", JSONObject(collection.payload.encoded).getString("global_collection_id"))
        val canonical = JSONObject("""{"global_collection_id":"global-only","specialname":"公开歌单"}""")
            .toKugouContentCollection(null, false)
        assertEquals("global-only", canonical.kugouGlobalCollectionId())
        val zeroSpecial = JSONObject("""{"SpecialID":0,"gid":"123456","specialname":"全局歌单"}""")
            .toKugouContentCollection(null, false)
        assertEquals(collection.stableId, zeroSpecial.stableId)
    }

    @Test fun transportCancellationIsNeverCaughtAsEmptyContent() = runBlocking {
        val cancelled = CancellationException("取消")
        val client = KugouContentClient(KugouContentTransport { _, _ -> throw cancelled })
        try {
            client.load(KugouContentRequest("/unused"), "")
            fail("应继续抛出取消")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }
}
