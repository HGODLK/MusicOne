package com.musicone.source.webdav.internal

import org.junit.Assert.*
import org.junit.Test

class WebDavIdentityAndLyricsTest {
    @Test
    fun stableIdentityDependsOnServerUserAndPath() {
        val serverA = WebDavServer("k1", "NAS", "http://192.168.1.2:5244/dav", "", "alice", "p1")
        val serverB = WebDavServer("k2", "NAS", "http://192.168.1.2:5244/dav", "", "bob", "p2")

        val id1 = WebDavIdentity.trackStableId(serverA, "/Album/01.flac")
        val id2 = WebDavIdentity.trackStableId(serverA, "/Album/01.flac/")
        val id3 = WebDavIdentity.trackStableId(serverB, "/Album/01.flac")

        assertEquals(id1, id2)
        assertNotEquals(id1, id3)
    }

    @Test
    fun mimeTypeRoutingMatchesHostNativeDecoderRules() {
        assertEquals("audio/x-dsf", WebDavPaths.resolveMimeType("track.dsf"))
        assertEquals("audio/x-dff", WebDavPaths.resolveMimeType("track.dff"))
        assertEquals("audio/x-ape", WebDavPaths.resolveMimeType("track.ape"))
        assertEquals("audio/x-wavpack", WebDavPaths.resolveMimeType("track.wv"))
        assertEquals("audio/mp4", WebDavPaths.resolveMimeType("track.m4a"))
        assertEquals("audio/alac", WebDavPaths.resolveMimeType("track.m4a", detectedCodecMime = "audio/alac"))
        assertEquals("audio/flac", WebDavPaths.resolveMimeType("track.FLAC"))
    }

    @Test
    fun encodesSpacesAsPercent20AndPreservesSlashes() {
        val server = WebDavServer("k1", "NAS", "http://192.168.1.2:5244/dav", "/音乐", "u", "p")
        val url = WebDavPaths.buildRequestUrl(server, "/A B/测试 #1.flac")
        assertEquals("http://192.168.1.2:5244/dav/%E9%9F%B3%E4%B9%90/A%20B/%E6%B5%8B%E8%AF%95%20%231.flac", url)
    }

    @Test
    fun parsesLrcWithMultiTimestampsOffsetAndTranslation() {
        val lrc = """
            [ti:测试曲]
            [offset:100]
            [00:10.50][01:00.00]Hello World
            [00:10.50]你好世界
            [00:20.125]Second Line
        """.trimIndent()

        val parsed = WebDavLyricsParser.parse(lrc)
        assertNotNull(parsed)
        assertEquals(3, parsed!!.lines.size)
        assertEquals(10_400L, parsed.lines[0].startMs)
        assertEquals("Hello World", parsed.lines[0].text)
        assertEquals("你好世界", parsed.lines[0].translation)
        assertEquals(20_025L, parsed.lines[1].startMs)
        assertEquals("Second Line", parsed.lines[1].text)
        assertEquals(59_900L, parsed.lines[2].startMs)
    }

    @Test
    fun extractsArtistAndTitleFromFileName() {
        val entry = WebDavEntry("/Jay/03. 周杰伦 - 晴天.flac", "03. 周杰伦 - 晴天.flac", false)
        val meta = WebDavMetadataReader.parseMetaFromFileName(entry)
        assertEquals("周杰伦", meta.artist)
        assertEquals("晴天", meta.title)
        assertEquals("Jay", meta.album)
    }
}
