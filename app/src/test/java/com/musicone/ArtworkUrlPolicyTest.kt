package com.musicone

import org.junit.Assert.*
import org.junit.Test

class ArtworkUrlPolicyTest {
    @Test fun selfHostedAddressesKeepPlainHttp() {
        assertTrue("http://192.168.3.13:4533/rest/getCoverArt?id=1".keepsPlainHttp())
        assertTrue("http://10.0.0.2/cover.jpg".keepsPlainHttp())
        assertTrue("http://nas.local/cover.jpg".keepsPlainHttp())
        assertTrue("http://nas/cover.jpg".keepsPlainHttp())
        assertTrue("http://music.example.org:8080/cover.jpg".keepsPlainHttp())
    }

    @Test fun publicCdnAddressesStillUpgrade() {
        assertFalse("http://p1.music.126.net/a.jpg".keepsPlainHttp())
        assertFalse("http://8.8.8.8/a.jpg".keepsPlainHttp())
        assertFalse("https://192.168.3.13:4533/a.jpg".keepsPlainHttp())
    }

    @Test fun upgradedAddressFallsBackToOriginalHttp() {
        assertEquals("http://p1.music.126.net/a.jpg",
            artworkPlainHttpFallback("http://p1.music.126.net/a.jpg", "https://p1.music.126.net/a.jpg"))
        assertNull(artworkPlainHttpFallback("http://192.168.3.13:4533/a.jpg", "http://192.168.3.13:4533/a.jpg"))
        assertNull(artworkPlainHttpFallback("https://y.qq.com/a.jpg", "https://y.qq.com/a.jpg"))
    }
}
