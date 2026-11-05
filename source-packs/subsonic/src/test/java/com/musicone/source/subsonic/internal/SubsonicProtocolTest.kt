package com.musicone.source.subsonic.internal

import com.musicone.source.subsonic.plainText
import com.musicone.source.subsonic.subsonicMimeType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubsonicProtocolTest {
    @Test fun normalizesServerAddresses() {
        assertEquals("http://192.168.1.10:4533", normalizeSubsonicUrl(" 192.168.1.10:4533/ "))
        assertEquals("https://music.example.org/navidrome", normalizeSubsonicUrl("https://Music.Example.org/navidrome/rest/"))
        assertEquals("http://nas.local", normalizeSubsonicUrl("http://nas.local/rest"))
        assertThrows(IllegalArgumentException::class.java) { normalizeSubsonicUrl("ftp://nas.local") }
        assertThrows(IllegalArgumentException::class.java) { normalizeSubsonicUrl("   ") }
    }

    @Test fun nativeFormatsGetExplicitMimeHints() {
        assertEquals("audio/x-dsf", subsonicMimeType("dsf", "audio/dsd", 5645))
        assertEquals("audio/x-wavpack", subsonicMimeType("wv", "", 1629))
        assertEquals("audio/alac", subsonicMimeType("m4a", "audio/mp4", 2304))
        assertEquals("audio/mp4", subsonicMimeType("m4a", "audio/mp4", 256))
        assertEquals("audio/flac", subsonicMimeType("flac", "audio/flac", 1013))
    }

    @Test fun tokenFollowsTheSubsonicSpecification() {
        // Subsonic API 文档中的示例。
        assertEquals("26719a1196d2a940705a59634eb18eab", subsonicToken("sesame", "c19b2d"))
        assertEquals("736573616d65", hexEncode("sesame"))
    }

    @Test fun serverKeyIgnoresAddressCaseButNotUser() {
        assertEquals(subsonicServerKey("http://NAS:4533", "alice"), subsonicServerKey("http://nas:4533", "alice"))
        assertNotEquals(subsonicServerKey("http://nas:4533", "alice"), subsonicServerKey("http://nas:4533", "bob"))
    }

    @Test fun authParametersUseTokenUnlessLegacy() {
        val server = SubsonicServer("k", "http://nas", "alice", "salt", "token")
        val names = server.authParameters().map { it.first }
        assertTrue("t" in names && "s" in names && "p" !in names)
        val legacy = server.copy(legacyPasswordHex = "736573616d65").authParameters().toMap()
        assertEquals("enc:736573616d65", legacy["p"])
        assertFalse(legacy.containsKey("t"))
        assertFalse(server.toString().contains("token"))
    }

    @Test fun parsesResponseEnvelope() {
        val ok = parseSubsonicResponse("""{"subsonic-response":{"status":"ok","version":"1.16.1","openSubsonic":true}}""", 200)
        assertTrue(ok.optBoolean("openSubsonic"))
        val failure = assertThrows(SubsonicApiException::class.java) {
            parseSubsonicResponse("""{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong username or password"}}}""", 200)
        }
        assertEquals(40, failure.code)
        assertEquals("用户名或密码不正确", failure.message)
        assertThrows(SubsonicTransportException::class.java) { parseSubsonicResponse("<html>login</html>", 200) }
        assertThrows(SubsonicTransportException::class.java) { parseSubsonicResponse("", 502) }
    }

    @Test fun acceptsSingleObjectsWhereArraysAreExpected() {
        val value = JSONObject("""{"a":{"id":"1"},"b":[{"id":"2"},{"id":"3"}]}""")
        assertEquals(listOf("1"), value.objects("a").map { it.getString("id") })
        assertEquals(listOf("2", "3"), value.objects("b").map { it.getString("id") })
        assertTrue(value.objects("missing").isEmpty())
    }

    @Test fun parsesOpenSubsonicSongArtists() {
        val song = JSONObject("""{"id":"s1","title":"Song","album":"Album","albumId":"a1","artist":"A feat. B",
            "artists":[{"id":"x","name":"A"},{"id":"y","name":"B"}],"duration":215,"suffix":"FLAC","bitRate":1012,
            "coverArt":"al-a1","starred":"2026-01-01T00:00:00Z","track":2,"discNumber":1}""").song()!!
        assertEquals(listOf("A", "B"), song.artists.map { it.name })
        assertEquals("A feat. B", song.artist)
        assertEquals(215_000L, song.durationMs)
        assertEquals("flac", song.suffix)
        assertTrue(song.starred)
        val legacy = JSONObject("""{"id":"s2","title":"Old","artist":"Solo","artistId":"z"}""").song()!!
        assertEquals(listOf(SubsonicNamedRef("z", "Solo")), legacy.artists)
        assertNull(JSONObject("""{"title":"no id"}""").song())
    }

    @Test fun sortsAlbumSongsByDiscAndTrack() {
        fun song(id: String, disc: Int, track: Int) = JSONObject().put("id", id).put("discNumber", disc).put("track", track).song()!!
        val ordered = listOf(song("c", 2, 1), song("b", 1, 2), song("a", 1, 1), song("x", 1, 0)).inAlbumOrder()
        assertEquals(listOf("a", "b", "x", "c"), ordered.map { it.id })
    }

    @Test fun stripsBiographyMarkup() {
        assertEquals("Line one\nLine two Read more", plainText("Line one<br/>Line two <a href=\"x\">Read more</a>"))
        assertEquals("Tom & Jerry", plainText("Tom &amp; Jerry"))
    }
}
