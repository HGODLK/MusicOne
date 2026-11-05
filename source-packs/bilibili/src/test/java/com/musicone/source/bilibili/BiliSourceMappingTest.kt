package com.musicone.source.bilibili

import org.junit.Assert.*
import org.junit.Test

class BiliSourceMappingTest {
    private val video = BiliVideoItem(123L, "BV1xz3m63Ek3", 0L, "视频", "https://example.test/cover", "作者", 600L)
    @Test fun contentRetainsItsRealArtworkAndPlayableFallback() {
        val content = BiliSourceMapping.content(video, "42")
        assertEquals(video.cover, content.artwork.url)
        assertTrue(content.playableTrack!!.access.playable)
        assertEquals(0L, BiliSourceMapping.cid(content.playableTrack!!))
        assertEquals(video.bvid, BiliSourceMapping.bvid(content.playableTrack!!))
    }
    @Test fun invalidVideoKeepsPartIdentityAndCanRecoverOnNextRefresh() {
        val part = BiliPagePart(11L, 1, "第一首", 1000L)
        val track = BiliSourceMapping.track(video, part, "42", true)
        val invalid = BiliSourceMapping.track(video.copy(isInvalid = true), part, "42", true)
        assertEquals(track.stableId, invalid.stableId)
        assertFalse(invalid.access.playable)
        assertEquals(track, BiliSourceMapping.track(video, part, "42", true))
    }
    @Test fun multiPartTitlePutsPartBeforeVideoTitle() {
        assertEquals("第一首 视频", BiliSourceMapping.track(video, BiliPagePart(11L, 1, "第一首", 1000L), "42", true).track.title)
        assertEquals("P2 视频", BiliSourceMapping.track(video, BiliPagePart(12L, 2, " ", 1000L), "42", true).track.title)
        assertEquals("视频", BiliSourceMapping.track(video, BiliPagePart(13L, 3, "视频", 1000L), "42", true).track.title)
        assertEquals("视频", BiliSourceMapping.track(video, BiliPagePart(14L, 1, "第一首", 1000L), "42", false).track.title)
        assertEquals(1000L, BiliSourceMapping.track(video, BiliPagePart(11L, 1, "第一首", 1000L), "42", true).durationMs)
    }
    @Test fun contentPayloadKeepsPageCount() {
        assertEquals(3, BiliSourceMapping.video(BiliSourceMapping.content(video.copy(pageCount = 3), "42")).pageCount)
    }
}
