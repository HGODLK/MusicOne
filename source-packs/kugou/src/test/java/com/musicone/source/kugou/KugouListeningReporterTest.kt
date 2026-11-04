package com.musicone.source.kugou

import com.musicone.source.sdk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class KugouListeningReporterTest {
    private val cookie = "userid=42; token=test; KUGOU_API_MID=mid; KUGOU_API_GUID=01234567-89ab-cdef-0123-456789abcdef"
    private val track = SourceTrackDetails(SourceTrack(TrackIdentity(KUGOU_SOURCE_ID, "42", "HASH"), "歌曲",
        opaqueData = mapOf("catalogId" to "77")), "kugou-HASH")
    private fun event(type: SourcePlaybackSessionEventType, sequence: Long, played: Long = 0,
        reason: SourcePlaybackSessionEndReason? = null, id: String = "session", position: Long = played) =
        SourcePlaybackSessionEvent(id, sequence, track, type, played, position, 1_000_000 + played, reason)

    private class Client : KugouListeningService {
        val seconds = mutableListOf<Long>()
        val events = mutableListOf<Triple<Boolean, Long, Boolean>>()
        var queries = 0
        var failQuery = false
        var failWrite = false
        var failPlayback = false
        override suspend fun baseline(cookie: String, check: () -> Unit): Long {
            check(); queries++; if (failQuery) error("读取失败"); return 500
        }
        override suspend fun sync(cookie: String, baselineSec: Long, addedSec: Long, check: () -> Unit): Long {
            check(); assertEquals(500L, baselineSec); seconds += addedSec
            if (failWrite) error("写入结果不明确")
            return 500 + addedSec
        }
        override suspend fun playback(cookie: String, songId: Long, started: Boolean, playedMs: Long,
            completed: Boolean, occurredAtMs: Long, check: () -> Unit) {
            check(); assertEquals(77L, songId); events += Triple(started, playedMs, completed)
            if (failPlayback) error("播放事件失败")
        }
    }

    @Test fun pauseResumeSettlesOnlyNewRealSecondsAndNeverSeekPosition() = runBlocking {
        val client = Client()
        val owner = KugouAccountRequest(cookie, 0)
        val reporter = KugouListeningReporter({ owner }, { assertEquals(owner, it) }, client) {}
        reporter.onPlaybackSession(event(SourcePlaybackSessionEventType.START, 1))
        reporter.onPlaybackSession(event(SourcePlaybackSessionEventType.PROGRESS, 2, 1_900, position = 200_000))
        assertTrue(client.seconds.isEmpty())
        reporter.onPlaybackSession(event(SourcePlaybackSessionEventType.PAUSE, 3, 1_900))
        reporter.onPlaybackSession(event(SourcePlaybackSessionEventType.RESUME, 4, 1_900))
        reporter.onPlaybackSession(event(SourcePlaybackSessionEventType.END, 5, 3_100, SourcePlaybackSessionEndReason.SWITCHED))
        assertEquals(listOf(1L, 2L), client.seconds)
        assertEquals(listOf(Triple(true, 0L, false), Triple(false, 3_100L, false)), client.events)
    }

    @Test fun duplicateAndReorderedEventsCannotReplayDurationOrEnd() = runBlocking {
        val client = Client()
        val reporter = KugouListeningReporter({ KugouAccountRequest(cookie, 0) }, {}, client) {}
        reporter.onPlaybackSession(event(SourcePlaybackSessionEventType.START, 1))
        reporter.onPlaybackSession(event(SourcePlaybackSessionEventType.PAUSE, 3, 5_000))
        reporter.onPlaybackSession(event(SourcePlaybackSessionEventType.PAUSE, 2, 6_000))
        val end = event(SourcePlaybackSessionEventType.END, 4, 6_000, SourcePlaybackSessionEndReason.COMPLETED)
        reporter.onPlaybackSession(end)
        reporter.onPlaybackSession(end)
        reporter.onPlaybackSession(event(SourcePlaybackSessionEventType.START, 5))
        assertEquals(listOf(5L, 1L), client.seconds)
        assertEquals(2, client.events.size)
        assertTrue(client.events.last().third)
    }

    @Test fun accountGenerationChangeDropsOriginalSessionEvenWhenUserIdIsUnchanged() = runBlocking {
        val client = Client()
        var generation = 0L
        val reporter = KugouListeningReporter({ KugouAccountRequest(cookie, generation) }, {
            check(it.generation == generation)
        }, client) {}
        reporter.onPlaybackSession(event(SourcePlaybackSessionEventType.START, 1))
        generation++
        reporter.onPlaybackSession(event(SourcePlaybackSessionEventType.END, 2, 50_000))
        assertTrue(client.seconds.isEmpty())
        assertEquals(1, client.events.size)
    }

    @Test fun unknownWriteResultIsNotRetriedAndCsccFailureDoesNotBlockGrade() = runBlocking {
        val client = Client().apply { failWrite = true; failPlayback = true }
        val reporter = KugouListeningReporter({ KugouAccountRequest(cookie, 0) }, {}, client) {}
        reporter.onPlaybackSession(event(SourcePlaybackSessionEventType.START, 1))
        reporter.onPlaybackSession(event(SourcePlaybackSessionEventType.PAUSE, 2, 5_000))
        reporter.onPlaybackSession(event(SourcePlaybackSessionEventType.END, 3, 7_000))
        assertEquals(listOf(5L, 2L), client.seconds)
        assertEquals(1, client.events.size)
    }

    @Test fun lateCatalogIdDoesNotCreateAnEndWithoutAConfirmedStart() = runBlocking {
        val client = Client()
        val reporter = KugouListeningReporter({ KugouAccountRequest(cookie, 0) }, {}, client) {}
        val start = event(SourcePlaybackSessionEventType.START, 1)
        reporter.onPlaybackSession(start.copy(track = track.copy(track = track.track.copy(opaqueData = emptyMap()))))
        reporter.onPlaybackSession(event(SourcePlaybackSessionEventType.END, 2, 5_000))
        assertTrue(client.events.isEmpty())
        assertEquals(listOf(5L), client.seconds)
    }

    @Test fun failedReadCanRetryAtEndBecauseNoDurationWriteWasSent() = runBlocking {
        val client = Client().apply { failQuery = true }
        val reporter = KugouListeningReporter({ KugouAccountRequest(cookie, 0) }, {}, client) {}
        reporter.onPlaybackSession(event(SourcePlaybackSessionEventType.START, 1))
        reporter.onPlaybackSession(event(SourcePlaybackSessionEventType.PAUSE, 2, 5_500))
        client.failQuery = false
        reporter.onPlaybackSession(event(SourcePlaybackSessionEventType.END, 3, 6_500))
        assertEquals(listOf(6L), client.seconds)
    }

    @Test fun guestAndForeignAccountDoNotSendDurationOrCreateCscc() = runBlocking {
        val client = Client()
        for (credential in listOf("", cookie.replace("userid=42", "userid=43"))) {
            val reporter = KugouListeningReporter({ KugouAccountRequest(credential, 0) }, {}, client) {}
            reporter.onPlaybackSession(event(SourcePlaybackSessionEventType.START, 1))
            reporter.onPlaybackSession(event(SourcePlaybackSessionEventType.END, 2, 5_000))
        }
        assertTrue(client.seconds.isEmpty())
        assertTrue(client.events.isEmpty())
    }
}
