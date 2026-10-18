package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class BiliSubscriptionTracksTest {
    private val video = BiliVideoItem(123L, "BV1xz3m63Ek3", 0L, "视频", "", "作者", 600L)

    @Test fun newVideoRemainsPlayableBeforeItsPartsAreRequested() {
        val track = biliSubscriptionTracks(video, emptyList()).single()
        assertTrue(track.playable)
        assertEquals(0L, track.bilibiliCid)
        assertEquals(video.bvid, track.bilibiliBvid)
    }

    @Test fun refreshingListKeepsExistingPartIdentitiesAndMetadata() {
        val first = BiliTrackMapper.toTrack(video, BiliPagePart(11L, 1, "第一首", 1000L), true)
        val second = BiliTrackMapper.toTrack(video, BiliPagePart(12L, 2, "第二首", 2000L), true)
        val unrelated = BiliTrackMapper.toFallbackTrack(video.copy(bvid = "BV1G2uo66EvG"))
        assertEquals(listOf(first, second), biliSubscriptionTracks(video, listOf(first, second, unrelated)))
    }

    @Test fun invalidVideoKeepsEveryExistingPartAndCanRecoverOnNextRefresh() {
        val track = BiliTrackMapper.toTrack(video, BiliPagePart(11L, 1, "第一首", 1000L), true)
        val invalid = biliSubscriptionTracks(video.copy(isInvalid = true), listOf(track)).single()
        assertEquals(track.id, invalid.id)
        assertFalse(invalid.playable)
        val restored = biliSubscriptionTracks(video, listOf(invalid)).single()
        assertEquals(track, restored)
    }
}
