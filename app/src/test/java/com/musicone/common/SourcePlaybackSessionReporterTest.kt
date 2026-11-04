package com.musicone

import com.musicone.sourceintegration.SourcePlaybackSessionReporter
import com.musicone.source.sdk.*
import org.junit.Assert.*
import org.junit.Test

class SourcePlaybackSessionReporterTest {
    private val track = MusicTrack("song", MusicSource.QQ, "歌曲", "歌手", "", 240_000, 0, 0, "", "")
    private class Fixture(enabled: Boolean = true) {
        var now = 0L
        var identities = 0
        var lookups = 0
        val events = mutableListOf<SourcePlaybackSessionEvent>()
        val reporter = SourcePlaybackSessionReporter(
            { lookups++; if (enabled) SourcePlaybackSessionObserver {} else null },
            { now }, { 1_000_000 + now }, { (++identities).toString() }, { _, event -> events += event },
        )
    }

    @Test fun disabledSourceDoesNotCreateOrDispatchSessions() {
        val f = Fixture(false)
        f.reporter.observe(track, false, 0)
        f.reporter.observe(track, true, 0)
        f.now = 1000
        f.reporter.observe(track, true, 1000, progress = true)
        f.reporter.close(1000)
        assertEquals(1, f.lookups)
        assertEquals(0, f.identities)
        assertTrue(f.events.isEmpty())
    }

    @Test fun seekAndBufferingDoNotAddPositionOrPausedTime() {
        val f = Fixture()
        f.reporter.observe(track, true, 0)
        f.now = 1000
        f.reporter.observe(track, true, 180_000, progress = true)
        f.now = 1500
        f.reporter.observe(track, false, 180_500)
        f.now = 9000
        f.reporter.observe(track, true, 180_500)
        f.now = 9500
        f.reporter.observe(track, false, 181_000, completed = true)
        assertEquals(listOf(0L, 1000L, 1500L, 1500L, 2000L), f.events.map { it.playedMs })
        assertEquals(listOf(SourcePlaybackSessionEventType.START, SourcePlaybackSessionEventType.PROGRESS,
            SourcePlaybackSessionEventType.PAUSE, SourcePlaybackSessionEventType.RESUME,
            SourcePlaybackSessionEventType.END), f.events.map { it.type })
        assertEquals(SourcePlaybackSessionEndReason.COMPLETED, f.events.last().endReason)
        assertEquals(1, f.events.map { it.sessionId }.distinct().size)
    }

    @Test fun qualityRecoveryRetainsSessionButCompletedRepeatStartsAnother() {
        val f = Fixture()
        f.reporter.observe(track, true, 0)
        f.now = 1000
        f.reporter.prepare(track, 1000, continuing = true)
        f.now = 8000
        f.reporter.observe(track, true, 1000)
        f.now = 9000
        f.reporter.observe(track, false, 2000, completed = true)
        f.reporter.observe(track, true, 0)
        assertEquals(listOf("1", "1", "1", "1", "2"), f.events.map { it.sessionId })
        assertEquals(2000L, f.events[3].playedMs)
        assertEquals(0L, f.events.last().playedMs)
    }

    @Test fun switchAndReleaseEachSettleOnlyOnceWithIncreasingSequence() {
        val f = Fixture()
        f.reporter.observe(track, true, 0)
        f.now = 1200
        f.reporter.prepare(track.copy(id = "next"), 1200, continuing = false)
        f.reporter.observe(track.copy(id = "next"), true, 0)
        f.now = 1500
        f.reporter.close(300)
        f.reporter.finish(SourcePlaybackSessionEndReason.STOPPED)
        assertEquals(listOf(1200L, 300L), f.events.filter { it.type == SourcePlaybackSessionEventType.END }.map { it.playedMs })
        assertEquals(listOf(SourcePlaybackSessionEndReason.SWITCHED, SourcePlaybackSessionEndReason.RELEASED),
            f.events.mapNotNull { it.endReason })
        assertEquals(listOf(1L, 2L, 1L, 2L), f.events.map { it.sequence })
    }
}
