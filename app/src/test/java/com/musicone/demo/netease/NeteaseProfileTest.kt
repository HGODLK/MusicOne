package com.musicone.demo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseProfileTest {

    @Test
    fun customBackgroundIsOptIn() {
        assertEquals(
            "file:///data/user/0/io.github.killy.musicone/files/profile/custom.webp",
            neteaseProfileBackgroundSource("file:///data/user/0/io.github.killy.musicone/files/profile/custom.webp"),
        )
        assertEquals(null, neteaseProfileBackgroundSource(null))
    }
    @Test
    fun `account keeps profile appearance and social counts`() {
        val account = JSONObject(
            """{
                "profile": {
                    "userId": 42,
                    "nickname": "测试用户",
                    "avatarUrl": "http://p1.music.126.net/avatar.jpg",
                    "backgroundUrl": "http://p1.music.126.net/background.jpg",
                    "signature": "音乐签名",
                    "follows": 12,
                    "followeds": 345,
                    "vipType": 11
                }
            }""",
        ).toMusicAccount()

        assertEquals("https://p1.music.126.net/background.jpg", account.backgroundUrl)
        assertEquals("音乐签名", account.signature)
        assertEquals(12, account.follows)
        assertEquals(345, account.followers)
        assertTrue(account.hasVipAccess)
    }

    @Test
    fun `user playlists keep artwork and track count`() {
        val playlists = JSONObject(
            """{
                "playlist": [{
                    "id": 9001,
                    "name": "我喜欢的音乐",
                    "trackCount": 88,
                    "coverImgUrl": "http://p1.music.126.net/cover.jpg",
                    "creator": {"userId": 42, "nickname": "测试用户"}
                }]
            }""",
        ).parseUserPlaylists("42")

        assertEquals(1, playlists.size)
        assertEquals("netease-9001", playlists.single().id)
        assertEquals(88, playlists.single().count)
        assertEquals("https://p1.music.126.net/cover.jpg", playlists.single().artworkUrl)
        assertTrue(playlists.single().isOwned)
    }

    @Test
    fun `background crop keeps requested ratio and image bounds`() {
        val centered = neteaseCropRect(
            sourceWidth = 2_000,
            sourceHeight = 1_000,
            selection = NeteaseCropSelection(aspectRatio = .5f),
        )
        assertEquals(500, centered.width)
        assertEquals(1_000, centered.height)
        assertEquals(750, centered.x)
        assertEquals(0, centered.y)

        val zoomedEdge = neteaseCropRect(
            sourceWidth = 2_000,
            sourceHeight = 1_000,
            selection = NeteaseCropSelection(centerX = 1f, centerY = 0f, zoom = 2f, aspectRatio = .5f),
        )
        assertEquals(250, zoomedEdge.width)
        assertEquals(500, zoomedEdge.height)
        assertEquals(1_750, zoomedEdge.x)
        assertEquals(0, zoomedEdge.y)
    }

    @Test
    fun `large profile counts remain compact`() {
        assertEquals("9999", formatProfileCount(9_999))
        assertEquals("1.2万", formatProfileCount(12_345))
        assertEquals("12万+", formatProfileCount(120_000))
    }
}
