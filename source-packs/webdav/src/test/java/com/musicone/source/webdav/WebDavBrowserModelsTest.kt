package com.musicone.source.webdav

import com.musicone.source.sdk.SourcePlayerState
import com.musicone.source.sdk.SourceTrack
import com.musicone.source.sdk.SourceTrackDetails
import com.musicone.source.sdk.TrackIdentity
import com.musicone.source.webdav.internal.WEBDAV_SOURCE_ID
import com.musicone.source.webdav.internal.WebDavEntry
import com.musicone.source.webdav.internal.webDavArtwork
import com.musicone.source.webdav.internal.withCover
import org.junit.Assert.*
import org.junit.Test

class WebDavBrowserModelsTest {
    private fun track(id: String, title: String, artist: String = ""): SourceTrackDetails = SourceTrackDetails(
        track = SourceTrack(TrackIdentity(WEBDAV_SOURCE_ID, "acc", id), title, listOf(artist).filter { it.isNotEmpty() }),
        stableId = id,
        artwork = webDavArtwork(null, title),
        artistsText = artist,
    )

    private fun dir(path: String) = WebDavEntry(path, path.substringAfterLast('/'), isDirectory = true)

    @Test
    fun sortKeepsFileOrderAndPutsMissingArtistLast() {
        val tracks = listOf(track("1", "B", ""), track("2", "C", "乙"), track("3", "A", "甲"))
        assertEquals(listOf("1", "2", "3"), sortedWebDavTracks(tracks, WebDavTrackSort.FILE).map { it.stableId })
        assertEquals(listOf("3", "1", "2"), sortedWebDavTracks(tracks, WebDavTrackSort.TITLE).map { it.stableId })
        assertEquals("1", sortedWebDavTracks(tracks, WebDavTrackSort.ARTIST).last().stableId)
        assertEquals(WebDavTrackSort.FILE, WebDavTrackSort.of("unknown"))
    }

    @Test
    fun queryMatchesTitleAndArtistIgnoringCase() {
        val tracks = listOf(track("1", "Hello"), track("2", "晴天", "Jay"))
        assertEquals(2, tracks.matchingWebDavQuery("  ").size)
        assertEquals(listOf("1"), tracks.matchingWebDavQuery("hELLO").map { it.stableId })
        assertEquals(listOf("2"), tracks.matchingWebDavQuery("jay").map { it.stableId })
    }

    @Test
    fun treeShowsOnlyExpandedBranchesAndMarksKnownLeaves() {
        val children = mapOf("" to listOf(dir("/a"), dir("/b")), "/a" to listOf(dir("/a/c")), "/b" to emptyList())
        val nodes = buildWebDavTree("根", children, mapOf("" to true, "/a" to true))
        assertEquals(listOf("", "/a", "/a/c", "/b"), nodes.map { it.path })
        assertEquals(listOf(0, 1, 2, 1), nodes.map { it.depth })
        assertTrue(nodes.first { it.path == "/b" }.leaf)
        assertFalse("未读取过的目录仍允许展开", nodes.first { it.path == "/a/c" }.leaf)
    }

    @Test
    fun ancestorsAndDepthFollowPath() {
        assertEquals(emptyList<String>(), webDavAncestors(""))
        assertEquals(listOf("", "/a", "/a/b"), webDavAncestors("/a/b/c"))
        assertEquals(0, webDavDepth(""))
        assertEquals(3, webDavDepth("/a/b/c"))
    }

    @Test
    fun playingTrackUsesHostIdentityInsteadOfPackageId() {
        val song = track("wd_1", "晴天")
        val hostId = webDavHostTrackId(song)
        val ids = mapOf(hostId to song.stableId)
        // 固定样例：3:acc:4:wd_1 的 SHA-256，与宿主换算一致。
        assertEquals("$WEBDAV_SOURCE_ID:5e11edd6d46a13276cde3dcf3f6cd5bd93cc3f4feee1b394cd3ab70fbcd5a90d", hostId)
        assertEquals("wd_1", playingWebDavTrackId(SourcePlayerState(WEBDAV_SOURCE_ID, hostId, true), ids))
        assertNull(playingWebDavTrackId(SourcePlayerState(WEBDAV_SOURCE_ID, "wd_1", true), ids))
        assertNull(playingWebDavTrackId(SourcePlayerState("other", hostId, true), ids))
    }

    @Test
    fun coverOnlyFillsMissingArtworkAndKeepsVisiblePlaceholder() {
        val song = track("1", "晴天")
        assertNotEquals("占位颜色不能透明", 0L, song.artwork.startArgb)
        val covered = song.withCover("file:/cover.jpg")
        assertEquals("file:/cover.jpg", covered.artwork.url)
        assertEquals("file:/cover.jpg", covered.track.artworkUrl)
        assertEquals(song.artwork.startArgb, covered.artwork.startArgb)
        assertEquals("file:/cover.jpg", covered.withCover("file:/other.jpg").artwork.url)
        assertSame(song, song.withCover(null))
    }
}
