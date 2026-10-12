package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class ContinuousLyricClockTest {
    @Test fun playingUsesSamplingTimeWithoutTheOldOneSecondCap() {
        assertEquals(12_500L, continuousLyricPosition(10_000, 1_000, 3_500, true, false, 210_000))
        assertEquals(10_319L, continuousLyricPosition(10_000, 1_000, 1_319, true, false, 210_000))
    }

    @Test fun pauseBufferingAndSeekFreezeAtTheActualPosition() {
        assertEquals(10_200L, continuousLyricPosition(10_200, 1_000, 9_000, false, false, 210_000))
        assertEquals(50_000L, continuousLyricPosition(50_000, 1_000, 9_000, true, true, 210_000))
        assertEquals(5_000L, continuousLyricPosition(5_000, 9_000, 9_010, false, true, 210_000))
        assertEquals(5_010L, continuousLyricPosition(5_000, 9_000, 9_010, true, false, 210_000))
    }

    @Test fun missingSampleAndTrackEndDoNotInventPlayback() {
        assertEquals(12L, continuousLyricPosition(12, 0, 9_000, true, false, 210_000))
        assertEquals(210_000L, continuousLyricPosition(209_800, 1_000, 2_000, true, false, 210_000))
    }

    @Test fun wordSnapshotUsesNewestPositionAndRejectsOtherPlaybackGenerations() {
        val window = LyricWindowPlayback("song")
        window.update(PlaybackProgressSnapshot("song", 10_000, 1, 1_000))
        val latest = PlaybackProgressSnapshot("song", 10_400, 1, 1_400)
        assertEquals(latest, window.wordSnapshot(latest, null))
        assertEquals(5_000L, window.wordSnapshot(latest, 5_000).positionMs)
        assertEquals(10_000L, window.wordSnapshot(latest.copy(generation = 2), null).positionMs)
        window.intermediatePositionOwned = true
        assertEquals(10_000L, window.wordSnapshot(latest, null).positionMs)
    }

    @Test fun emphasisIsSubtleAndRepeatedWordsKeepTheirOwnRanges() {
        val words = listOf(LyricWord(0, 100, "光"), LyricWord(100, 200, "光"), LyricWord(200, 200, "。"))
        val line = TimedLyric(0, "光光。", words = words)
        assertEquals(listOf(0, 1, 2), lyricWordRanges(line).map { it.start })
        assertEquals(.8f, lyricWordAlpha(words[0], -1), .00001f)
        assertEquals(.9f, lyricWordAlpha(words[0], 50), .00001f)
        assertEquals(1f, lyricWordAlpha(words[0], 100), .00001f)
        assertEquals(.8f, lyricWordAlpha(words[1], 50), .00001f)
        assertEquals(1f, lyricWordAlpha(words[2], 200), .00001f)
    }

    @Test fun currentLrcAndLegacyQrcNeedRefreshButCurrentQrcDoesNot() {
        val track = MusicTrack("qq-song", MusicSource.QQ, "", "", "", 210_000, 0, 0, "", "")
        val qrc = listOf(TimedLyric(0, "光", words = listOf(LyricWord(0, 100, "光"))))
        assertTrue(needsVerbatimLyricsRefresh(track.copy(lyrics = qrc)))
        assertTrue(needsVerbatimLyricsRefresh(track.copy(lyrics = listOf(TimedLyric(0, "光")).confirmedByCurrentQqParser())))
        assertFalse(needsVerbatimLyricsRefresh(track.copy(lyrics = qrc.confirmedByCurrentQqParser())))
        assertFalse(needsVerbatimLyricsRefresh(track.copy(source = MusicSource.NETEASE)))
    }
}
