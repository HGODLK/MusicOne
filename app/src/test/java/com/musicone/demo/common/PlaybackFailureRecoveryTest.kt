package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class PlaybackFailureRecoveryTest {
    @Test fun neteaseHttpStatusFailureRenewsAddressOnlyOnce() {
        val recovery = PlaybackFailureRecovery()
        val track = track("netease-3411982875", MusicSource.NETEASE)

        assertFalse(recovery.mayRenew(track))
        assertTrue(recovery.mayRenew(track, httpStatusFailure = true))
        assertFalse(recovery.mayRenew(track, httpStatusFailure = true))
    }

    @Test fun nonHttpNeteaseFailureDoesNotRenewAddress() {
        val recovery = PlaybackFailureRecovery()
        val track = track("netease-1", MusicSource.NETEASE)

        assertFalse(recovery.mayRenew(track, httpStatusFailure = false))
    }

    @Test fun bilibiliRenewalBehaviorRemainsUnchanged() {
        val recovery = PlaybackFailureRecovery()
        val track = track("bilibili-1", MusicSource.BILIBILI)

        assertTrue(recovery.mayRenew(track))
        assertFalse(recovery.mayRenew(track))
    }

    private fun track(id: String, source: MusicSource) =
        MusicTrack(id, source, "歌曲", "歌手", "专辑", 180_000L, 0, 0, "", "")
}
