package com.musicone.demo

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QqMusicFeedSongShelvesTest {
    @Test fun sameShelfWithDifferentSongsKeepsOneStableContainer() {
        val first = shelf("207", page("a", "b", "c"))
        val later = shelf("207", page("d", "e", "f"))
        val flow = song("flow")
        val merged = appendQqMusicFeedDisplayCards(listOf(first, flow), listOf(later), false)
        val container = merged.first() as QqMusicFeedCard.SongShelf

        assertEquals(first.key, later.key)
        assertEquals(first.key, container.key)
        assertEquals(first.pages + later.pages, container.pages)
        assertSame(first.pages.first(), container.pages.first())
        assertSame(flow, merged[1])
        assertEquals(2, merged.size)
    }

    @Test fun threeDifferentShelvesStaySeparateEvenWhenTheirSongsOverlap() {
        val first = shelf("207", page("a", "b", "c"))
        val overlapping = shelf("208", page("a", "d", "e"))
        val repeatedPage = shelf("209", page("a", "b", "c"))
        val merged = normalizeQqMusicFlowCards(listOf(first, overlapping, repeatedPage))
        assertEquals(listOf(first, overlapping, repeatedPage), merged)
        assertEquals(3, merged.filterIsInstance<QqMusicFeedCard.SongShelf>().size)
        assertEquals(merged, normalizeQqMusicFlowCards(merged))
    }

    @Test fun sameShelfIdWithThreeDifferentTitlesRemainsThreeRows() {
        val shelves = (1..3).map { shelf("207", page("song-$it")).copy(title = "因为你听过歌曲$it") }
        val refreshedContents = shelves.map { it.copy(pages = listOf(page("new-${it.title}"))) }
        val merged = normalizeQqMusicFlowCards(shelves + refreshedContents)
        assertEquals(3, shelves.map { it.key }.distinct().size)
        assertEquals(3, merged.size)
        shelves.forEachIndexed { index, shelf ->
            val ready = merged[index] as QqMusicFeedCard.SongShelf
            assertEquals(shelf.key, ready.key)
            assertEquals(2, ready.pages.size)
            assertSame(shelf.pages.first(), ready.pages.first())
        }
    }

    @Test fun laterNewThemesDoNotCreateAFourthOfficialRow() {
        val shelves = (1..3).map { shelf("$it", page("song-$it")) }
        val fourth = shelf("4", page("new"))
        assertEquals(shelves, normalizeQqMusicFlowCards(shelves + fourth))
        assertTrue(unseenQqMusicFeedCards(listOf(fourth), shelves).isEmpty())
        assertEquals(listOf(fourth), appendQqMusicFeedDisplayCards(shelves, listOf(fourth), true))
    }

    @Test fun numericAndCanonicalIdsIdentifyTheSameServerPage() {
        val first = shelf("207", listOf(song("123").copy(track = song("123").track.copy(catalogId = "123"))))
        val detailed = first.copy(pages = listOf(listOf(first.pages[0][0].copy(
            track = first.pages[0][0].track.copy(id = "qq-001AbcdEF12345", songMid = "001AbcdEF12345"),
        ))))
        assertSame(first, normalizeQqMusicFlowCards(listOf(first, detailed)).single())
        assertTrue(unseenQqMusicFeedCards(listOf(first), listOf(detailed)).isEmpty())
    }

    @Test fun previousMergedSnapshotsRequireOneFreshRestore() {
        assertTrue(requiresQqFeedShelfLayoutRefresh(listOf(JSONObject("""{"kind":"song-shelf"}"""))))
        assertFalse(requiresQqFeedShelfLayoutRefresh(listOf(JSONObject("""{"kind":"song-shelf","shelfLayoutVersion":2}"""))))
        assertFalse(requiresQqFeedShelfLayoutRefresh(listOf(JSONObject("""{"kind":"song"}"""))))
    }

    @Test fun initialOfficialRowsDoNotWaitForLowerArtworkOrGroupPermissionLookup() {
        val official = shelf("207", page("a"))
        val ready = song("001AbcdEF12345").copy(track = song("001AbcdEF12345").track.copy(
            songMid = "001AbcdEF12345", artworkUrl = "https://example.test/cover.jpg",
        ))
        val missingArtwork = ready.copy(track = ready.track.copy(artworkUrl = null))
        val group = QqMusicFeedCard.SongGroup("完整专辑卡", (1..3).map { index ->
            ready.track.copy(id = "group-$index", albumMid = "album-$index")
        })
        assertTrue(hasQqMusicFeedMixedAlbums(group.tracks))
        assertEquals(listOf(official, ready), qqFeedInitialCandidates(listOf(official, ready, missingArtwork, group)))
        assertEquals(listOf(missingArtwork, group), qqFeedInitialCandidates(listOf(missingArtwork, group)))
    }

    @Test fun seenContainerOnlyFiltersWholeRepeatedPages() {
        val old = shelf("207", page("a", "b", "c"))
        val incoming = old.copy(pages = old.pages + listOf(page("a", "d", "e")))
        val ready = unseenQqMusicFeedCards(listOf(incoming), listOf(old)).single() as QqMusicFeedCard.SongShelf
        assertEquals(listOf(page("a", "d", "e")), ready.pages)
        assertTrue(unseenQqMusicFeedCards(listOf(old), listOf(old)).isEmpty())
    }

    @Test fun refreshReplacesOldPagesAndUnchangedNormalizationPreservesObject() {
        val old = shelf("207", page("old"))
        val fresh = shelf("207", page("new"))
        assertSame(fresh, appendQqMusicFeedDisplayCards(listOf(old), listOf(fresh), true).single())
        assertSame(old, normalizeQqMusicFlowCards(listOf(old, old)).single())
    }

    @Test fun interruptedAndRepeatedServerShelvesKeepOneBlockAndRawCursorCounts() {
        val response = JSONObject("""{"v_shelf":[
            {"id":"207","title":"官方推荐","v_niche":[
                {"style":10001,"v_card":[{"style":208,"type":200,"id":"1","title":"甲"}]},
                {"style":10004,"v_card":[{"style":302,"type":500,"id":"999","title":"歌单"}]},
                {"style":10001,"v_card":[{"style":208,"type":200,"id":"2","title":"乙"}]}]},
            {"id":"207","title":"官方推荐","v_niche":[
                {"style":10001,"v_card":[{"style":208,"type":200,"id":"3","title":"丙"}]}]}
        ]}""")
        val parsed = parseQqMusicFeed(response, false)
        val shelf = parsed.cards.filterIsInstance<QqMusicFeedCard.SongShelf>().single()
        assertEquals(listOf("甲", "乙", "丙"), shelf.pages.flatten().map { it.track.title })
        assertEquals(2, parsed.shelfCount)
        assertEquals(listOf("207", "207"), parsed.shelfIds)
    }

    @Test fun completeGroupMetadataAvoidsExtraDetailsButMissingAlbumStillRequiresLookup() {
        val complete = song("001AbcdEF12345").track.copy(
            songMid = "001AbcdEF12345", album = "专辑", albumMid = "album", durationMs = 180_000,
            artworkUrl = "https://example.test/cover.jpg",
        )
        assertFalse(needsQqFeedMetadata(complete, inSongGroup = true))
        assertTrue(needsQqFeedMetadata(complete.copy(album = "", albumMid = ""), inSongGroup = true))
        assertTrue(needsQqFeedMetadata(complete.copy(artworkUrl = null), inSongGroup = true))
        assertFalse(needsQqFeedMetadata(complete.copy(durationMs = 0, qualityIds = emptyMap()), inSongGroup = true))
        val numeric = song("123456").track.copy(catalogId = "123456", album = "", albumMid = "", artworkUrl = "https://example.test/cover.jpg")
        assertFalse(needsQqFeedMetadata(numeric, inSongGroup = false))
        assertTrue(needsQqFeedMetadata(numeric, inSongGroup = true))
    }

    private fun shelf(id: String, page: List<QqMusicFeedCard.Song>) =
        QqMusicFeedCard.SongShelf(id, "官方推荐", listOf(page))

    private fun page(vararg ids: String) = ids.map(::song)

    private fun song(id: String) = QqMusicFeedCard.Song(MusicTrack(
        id = "qq-$id", source = MusicSource.QQ, title = id, artists = "歌手", album = "",
        durationMs = 0, artworkStart = 0, artworkEnd = 0, artworkMark = id, previewUrl = "",
    ), "推荐语")
}
