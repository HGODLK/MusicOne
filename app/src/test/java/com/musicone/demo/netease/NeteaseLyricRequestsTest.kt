package com.musicone.demo

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class NeteaseLyricRequestsTest {
    @Test fun lineLyricsRetryAfterCooldownAndCredentialChanges() {
        var time = 0L
        var calls = 0
        val requests = NeteaseLyricRequests { time }
        val line = listOf(TimedLyric(1000, "普通歌词"))
        fun load(cookie: String = "账号一") = requests.load("歌曲", cookie) { calls++; line }
        assertEquals(line, load())
        assertEquals(line, load())
        assertEquals(1, calls)
        time = 600L * 1_000_000_000L
        load()
        assertEquals(2, calls)
        load("刷新后的凭据")
        assertEquals(3, calls)
    }

    @Test fun failurePreservesWordsAndCancellationDoesNotStartCooldown() {
        var time = 0L
        val requests = NeteaseLyricRequests { time }
        val words = listOf(TimedLyric(1000, "光", words = listOf(LyricWord(1000, 1500, "光"))))
        requests.load("歌曲", "") { words }
        time = 600L * 1_000_000_000L
        assertEquals(words, requests.load("歌曲", "") { throw java.io.IOException() })
        time += 30L * 1_000_000_000L
        assertEquals(words, requests.load("歌曲", "") { listOf(TimedLyric(1000, "光")) })
        try {
            requests.load("另曲", "") { throw CancellationException() }
            fail("取消应继续传播")
        } catch (_: CancellationException) { }
        assertEquals(words, requests.load("另曲", "") { words })
    }

    @Test fun requestContextIncludesLoginAndUsesObjectHeader() {
        val header = neteaseRequestHeader("MUSIC_U=token; __csrf=csrf; other=ignored", "device")
        assertEquals("token", header.getString("MUSIC_U"))
        assertEquals("csrf", header.getString("__csrf"))
        assertEquals("device", header.getString("deviceId"))
        assertFalse(header.has("other"))
        assertNotEquals(header.getString("requestId"), neteaseRequestHeader("", "device").getString("requestId"))
    }
}
