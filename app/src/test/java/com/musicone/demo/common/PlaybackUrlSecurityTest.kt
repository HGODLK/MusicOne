package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackUrlSecurityTest {
    @Test fun qqPlaybackUrlIsUpgradedToHttps() {
        assertEquals(
            "https://aqqmusic.tc.qq.com/song.m4a",
            "http://aqqmusic.tc.qq.com/song.m4a".upgradePlaybackUrlToHttps(),
        )
    }

    @Test fun qqPlaybackUsesCredentialResponseCdnFirst() {
        assertEquals(
            listOf(
                "https://aqqmusic.tc.qq.com/M500song.mp3?vkey=token",
            ),
            qqPlaybackUrlCandidates(
                listOf("M500song.mp3?vkey=token"),
                listOf("http://aqqmusic.tc.qq.com/"),
            ),
        )
    }

    @Test fun kugouPlaybackUsesHttpsBackupBeforeLegacyCleartextCdn() {
        assertEquals(
            "https://sharefs.kugou.com/song.mp3",
            pickKugouHttpsPlaybackUrl(listOf(
                "http://fs.youthandroid2.kugou.com/song.mp3",
                "https://sharefs.kugou.com/song.mp3",
            )),
        )
    }

    @Test fun kugouRejectsNestedLegacyCdnWithoutValidHttpsCertificate() {
        assertEquals(
            "",
            pickKugouHttpsPlaybackUrl(listOf("http://fs.youthandroid2.kugou.com/song.mp3")),
        )
    }
}
