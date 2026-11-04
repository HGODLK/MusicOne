package com.musicone.source.qq.internal

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class MigratedHostPlaybackUrlSecurityTest {
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

}
