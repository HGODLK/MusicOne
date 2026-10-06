package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkRepositoryTest {
    @Test
    fun qqAlbumArtworkHasEquivalentCdnFallbacks() {
        val candidates = artworkDownloadCandidates(
            "https://y.gtimg.cn/music/photo_new/T002R500x500M000003kZmTJ2kUUNc.jpg",
        )

        assertEquals(4, candidates.size)
        assertTrue(candidates.all { "003kZmTJ2kUUNc" in it })
        assertTrue(candidates.any { "T002R300x300" in it })
        assertTrue(candidates.any { it.startsWith("https://y.qq.com/") })
    }

    @Test
    fun unrelatedArtworkKeepsItsOnlyAddress() {
        val url = "https://example.com/cover.jpg"
        assertEquals(listOf(url), artworkDownloadCandidates(url))
    }
}
