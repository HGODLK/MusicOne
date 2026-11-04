package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackUrlSecurityTest {
    @Test fun qqPlaybackUrlIsUpgradedToHttps() {
        assertEquals(
            "https://aqqmusic.tc.qq.com/song.m4a",
            "http://aqqmusic.tc.qq.com/song.m4a".upgradePlaybackUrlToHttps(),
        )
    }

}
