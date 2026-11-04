package com.musicone.source.kugou

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class MigratedHostPlaybackUrlSecurityTest {
    @Test fun kugouExplicitHttpsBackupWinsOverUpgradeableHttpPrimary() {
        assertEquals(
            "https://backup.kugou.com/song.mp3",
            pickKugouPlaybackUrl(listOf(
                "http://cdn.kugou.com/song.mp3",
                "https://backup.kugou.com/song.mp3",
            )),
        )
    }

    @Test fun kugouPlaybackUsesHttpsBackupBeforeLegacyCleartextCdn() {
        assertEquals(
            "https://sharefs.kugou.com/song.mp3",
            pickKugouPlaybackUrl(listOf(
                "http://fs.youthandroid2.kugou.com/song.mp3",
                "https://sharefs.kugou.com/song.mp3",
            )),
        )
    }

    @Test fun kugouPreservesLegacyMediaUrlWithoutForcingAnInvalidHttpsCertificate() {
        assertEquals(
            "http://fs.youthandroid2.kugou.com/song.mp3?key=media-ticket",
            pickKugouPlaybackUrl(listOf("http://fs.youthandroid2.kugou.com/song.mp3?key=media-ticket")),
        )
    }

    @Test fun kugouDoesNotAcceptUnrelatedOrLookalikeCleartextHosts() {
        listOf("http://cdn.example.com/a.mp3", "http://kugou.com.example.com/a.mp3",
            "http://fakekugou.com/a.mp3").forEach { url ->
            assertEquals("", pickKugouPlaybackUrl(listOf(url)))
        }
    }

    @Test fun kugouOnlySendsAccountCookieToItsOwnHttpsMediaHosts() {
        assertEquals(null, kugouMediaHeaders("http://fs.youthandroid2.kugou.com/a.mp3", "token=account")["Cookie"])
        assertEquals(null, kugouMediaHeaders("https://cdn.example.com/a.mp3", "token=account")["Cookie"])
        assertEquals("token=account", kugouMediaHeaders("https://sharefs.kugou.com/a.mp3", "token=account")["Cookie"])
    }
}
