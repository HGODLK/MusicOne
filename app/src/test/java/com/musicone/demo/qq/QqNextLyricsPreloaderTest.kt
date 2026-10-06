package com.musicone.demo

import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QqNextLyricsPreloaderTest {
    private val track = MusicTrack("qq-next", MusicSource.QQ, "下一首", "歌手", "", 180_000, 0, 0, "", "")
    private val lyrics = listOf(TimedLyric(1_000, "原文", "翻译"))

    @Test fun fetchedLyricsAndTranslationAreReusedWithoutAnotherRequest() = runBlocking {
        val fixture = Fixture()
        fixture.loader.preload(track, "QQ:100")
        assertEquals(lyrics, fixture.cache["QQ:100"])
        assertEquals("cookie-100", fixture.usedCookie)
        fixture.loader.preload(track, "QQ:100")
        assertEquals(1, fixture.fetches)
        assertEquals(1, fixture.writes)
    }

    @Test fun existingCacheAndEmbeddedLyricsDoNotUseTheNetwork() = runBlocking {
        val cached = Fixture().apply { cache["QQ:100"] = lyrics }
        cached.loader.preload(track, "QQ:100")
        assertEquals(0, cached.fetches)
        assertEquals(0, cached.writes)
        val embedded = Fixture()
        embedded.loader.preload(track.copy(lyrics = lyrics), "QQ:100")
        assertEquals(0, embedded.fetches)
        assertEquals(lyrics, embedded.cache["QQ:100"])
    }

    @Test fun outdatedAccountDoesNotReadOrRequestLyrics() = runBlocking {
        val fixture = Fixture().apply { accountId = "200" }
        fixture.loader.preload(track, "QQ:100")
        assertEquals(0, fixture.reads)
        assertEquals(0, fixture.fetches)
        assertEquals(0, fixture.writes)
    }

    @Test fun accountChangeDuringCacheReadPreventsNetworkRequest() = runBlocking {
        val fixture = Fixture()
        fixture.onRead = { fixture.accountId = "200" }
        fixture.loader.preload(track, "QQ:100")
        assertEquals(0, fixture.fetches)
        assertEquals(0, fixture.writes)
    }

    @Test fun lateResponseCannotBeWrittenAfterAnAccountChange() = runBlocking {
        val fixture = Fixture()
        fixture.fetchResult = { fixture.accountId = "200"; lyrics }
        fixture.loader.preload(track, "QQ:100")
        assertEquals(1, fixture.fetches)
        assertTrue(fixture.cache.isEmpty())
        assertEquals(0, fixture.writes)
    }

    @Test fun cancellationDiscardsEvenANonSuspendingResponse() = runBlocking {
        val fixture = Fixture()
        fixture.fetchResult = { currentCoroutineContext().cancel(); lyrics }
        val job = launch { fixture.loader.preload(track, "QQ:100") }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, fixture.fetches)
        assertEquals(0, fixture.writes)
    }

    @Test fun emptyLyricsAndFailuresDoNotCreateCacheEntries() = runBlocking {
        val fixture = Fixture()
        fixture.fetchResult = { emptyList() }
        fixture.loader.preload(track, "QQ:100")
        assertEquals(0, fixture.writes)
        fixture.fetchResult = { throw IllegalStateException("歌词请求失败") }
        assertTrue(runCatching { fixture.loader.preload(track, "QQ:100") }.exceptionOrNull() is IllegalStateException)
        assertEquals(0, fixture.writes)
    }

    private inner class Fixture {
        var accountId = "100"
        val cache = mutableMapOf<String, List<TimedLyric>>()
        var reads = 0
        var fetches = 0
        var writes = 0
        var usedCookie = ""
        var onRead: () -> Unit = {}
        var fetchResult: suspend () -> List<TimedLyric> = { lyrics }
        val loader = QqNextLyricsPreloader(
            session = { PlatformSession(MusicSource.QQ, "cookie-$accountId", "device",
                MusicAccount(MusicSource.QQ, accountId, "用户", null)) },
            readCached = { _, namespace -> reads++; onRead(); cache[namespace].orEmpty() },
            fetch = { _, cookie -> fetches++; usedCookie = cookie; fetchResult() },
            save = { _, value, namespace -> writes++; cache[namespace] = value },
        )
    }
}
