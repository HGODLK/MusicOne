package com.musicone.source.kugou

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KugouHomeFeedTest {
    private fun track(index: Int) = MusicTrack("kugou-hash-$index", title = "歌曲$index", artists = "歌手$index",
        qualityIds = mapOf(AudioQuality.STANDARD to "hash-$index"))

    @Test fun homepageBatchCapsInsertedPlaylistsAtSixteenWithoutChangingSongsOrCollectionIdentity() {
        val lists = (1..19).map { MusicPlaylist("kugou-global:$it", title = "真实歌单$it", count = it) }
        val original = KugouHomePlaylistPage(lists, 2,
            lists.associate { it.id to mapOf("global_collection_id" to it.id.removePrefix("kugou-global:")) })
        val batch = original.asKugouHomeBatch()
        val songs = (1..15).map(::track)
        val sections = batch.homeSections("account", { it }, KugouHomeRecommendations("私人专属好歌", songs))
        assertEquals(lists.take(16).map { it.id }, sections.flatMap { it.collections }.map { it.stableId })
        assertEquals(songs.map { it.id }, sections.first().tracks.map { it.stableId })
        assertTrue(sections.drop(1).all { it.title == "这里有你想听的歌单" })
        assertEquals(16, batch.metadata.size)
        assertNull(batch.nextPage)
        assertEquals(19, original.playlists.size)
    }

    @Test fun partialEmbeddedSelectionsDoNotBecomeIndependentRecommendationsOrCompleteCache() {
        val title = "完整保留平台返回的很长歌单标题，包括符号｜和分类说明"
        val playlist = MusicPlaylist("kugou-global:gid", title = title, count = 100,
            tracks = (1..7).map(::track), artworkUrl = "https://img.kugou.com/cover.jpg")
        val cached = mutableListOf<MusicPlaylist>()
        val page = KugouHomePlaylistPage(listOf(playlist), 2,
            mapOf(playlist.id to mapOf("global_collection_id" to "gid", "list_create_listid" to "12")))
        val sections = page.homeSections("user", remember = { it.also(cached::add) })
        assertEquals(1, sections.size)
        val collection = sections.first().collections.single()
        assertEquals(title, collection.title)
        assertEquals(playlist.id, collection.stableId)
        assertEquals(100, collection.count)
        assertEquals("gid", JSONObject(collection.payload.encoded).getString("global_collection_id"))
        assertEquals("12", JSONObject(collection.payload.encoded).getString("list_create_listid"))
        assertTrue(sections.flatMap { it.tracks }.isEmpty())
        assertTrue(cached.single().tracks.isEmpty())
        assertEquals(2, page.nextPage)
    }

    @Test fun actualStandaloneRecommendationsUseOneExistingShelfAndKeepEveryTrack() {
        val tracks = (1..15).map(::track)
        val playlists = listOf(MusicPlaylist("kugou-1", title = "原标题一"), MusicPlaylist("kugou-2", title = "原标题二"))
        val sections = KugouHomePlaylistPage(playlists, 2).homeSections("user", { it },
            KugouHomeRecommendations("「精选好歌随心听」", tracks))
        assertEquals(3, sections.size)
        assertEquals("「精选好歌随心听」", sections.first().title)
        assertEquals(tracks.map { it.id }, sections.first().tracks.map { it.stableId })
        val layout = JSONObject(sections.first().payload.encoded)
        assertEquals("track-shelf", layout.getString("layout"))
        assertEquals(listOf(3, 3, 3, 3, 3), (0 until layout.getJSONArray("pageSizes").length())
            .map { layout.getJSONArray("pageSizes").getInt(it) })
        assertEquals(playlists.map { it.id }, sections.flatMap { it.collections }.map { it.stableId })
        assertTrue(sections.none { it.id.contains("daily") })
    }

    @Test fun absentEmbeddedSelectionsDoNotInventSongCardsOrDropPlaylists() {
        val lists = listOf(MusicPlaylist("kugou-1", title = "歌单一"), MusicPlaylist("kugou-2", title = "歌单二"))
        val cached = mutableListOf<MusicPlaylist>()
        val sections = KugouHomePlaylistPage(lists, 2).homeSections(null, remember = { it.also(cached::add) })
        assertEquals(lists.map { it.id }, sections.flatMap { it.collections }.map { it.stableId })
        assertTrue(sections.all { it.tracks.isEmpty() })
        assertEquals(2, cached.size)
        assertTrue(sections.none { it.id.contains("daily") })
    }
}
