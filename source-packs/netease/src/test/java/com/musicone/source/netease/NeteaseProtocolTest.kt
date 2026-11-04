package com.musicone.source.netease

import org.junit.Assert.*
import org.junit.Test

class NeteaseProtocolTest {
    @Test fun lrcParserSupportsMultipleTimestampsAndFractions() {
        val lines = parseLrc("[00:01.20][00:03.004]第一句\n[01:02]第二句\n[ar:歌手]")
        assertEquals(listOf(1_200L, 3_004L, 62_000L), lines.map(NeteaseLyricLine::timeMs))
        assertEquals(listOf("第一句", "第一句", "第二句"), lines.map(NeteaseLyricLine::text))
    }

    @Test fun neteaseEapiEncryptionMatchesKnownVector() {
        assertEquals(
            "1c0fb45b24bf45f8006f40710ab99ab37d400d166411c5ae8292ee9e129fc9aab8805876d72c7ee21377d45c2ccdaf5bc7ae8d46329b2df19916c58144af157b743d4b511578106d0dc75e6df0f82ab3d7d258396e2f570deed4b04578127c84",
            NeteaseCrypto.encryptEapi("/api/login/cellphone", "{\"e_r\":true}"),
        )
    }

    @Test fun neteaseMobileEapiResponseCanBeDecrypted() {
        val encrypted = "4dc12a14eca8f30f33123e619c7b9c893b1ae1dd553e6d9cc22204a313ce169d"
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
        assertEquals("{\"code\":200,\"message\":\"ok\"}", NeteaseCrypto.decryptMobileEapi(encrypted))
    }

    @Test fun neteaseCookieMergeReplacesValuesAndDropsAttributes() {
        assertEquals(
            "MUSIC_U=new; __csrf=token",
            mergeCookies("MUSIC_U=old; Path=/; HttpOnly", "MUSIC_U=new; __csrf=token; Max-Age=60"),
        )
    }

    @Test fun neteaseMediaUrlsAreUpgradedToHttps() {
        assertEquals("https://p1.music.126.net/cover.jpg", "http://p1.music.126.net/cover.jpg".asNeteaseHttpsUrl())
        assertEquals("https://m10.music.126.net/song.mp3", "https://m10.music.126.net/song.mp3".asNeteaseHttpsUrl())
    }

}
