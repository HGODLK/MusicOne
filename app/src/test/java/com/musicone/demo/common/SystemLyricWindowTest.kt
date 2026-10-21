package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test
import java.util.regex.Pattern

class SystemLyricWindowTest {
    private fun measure(text: String) = text.codePointCount(0, text.length).toFloat()
    private fun plan(text: String = "一二三四五六七八九十甲乙", end: Long? = 12_000L,
        width: Float = 6f, start: Long = 0L) =
        SystemLyricWindow.create(SystemLyricInterval(text, start, end), width, ::measure)

    private fun frames(value: SystemLyricWindow, start: Long = 0L): List<Pair<Long, String>> {
        val result = mutableListOf<Pair<Long, String>>()
        var position = start
        repeat(100) {
            val frame = value.at(position)
            result += position to frame.text
            position = frame.nextPositionMs ?: return result
        }
        error("分段不应无限循环")
    }

    @Test fun shortLinesRemainStaticWithoutPeriodicRotation() {
        val value = plan("短句")
        assertEquals(SystemLyricWindowFrame("短句"), value.at(0))
        assertEquals(value.at(0), value.at(99_999))
    }

    @Test fun windowsOverlapAndTailRemainsUntilTheNextLine() {
        val value = plan()
        val all = frames(value)
        assertEquals("一二三四五六", all.first().second)
        assertTrue(all[1].second.startsWith("六"))
        assertTrue(all.last().second.endsWith("甲乙"))
        assertTrue(all.zipWithNext().all { (a, b) -> b.first - a.first >= 1_500L })
        assertEquals(all.last().second, value.at(11_999).text)
        assertNull(value.at(11_999).nextPositionMs)
    }

    @Test fun actualMeasuredWidthDeterminesCapacityAcrossScripts() {
        val value = SystemLyricWindow.create(SystemLyricInterval("ABCD天地玄黄", 0, 12_000), 8f) { text ->
            text.sumOf { if (it.code < 128) 1 else 2 }.toFloat()
        }
        assertEquals("ABCD天地", value.at(0).text)
        assertTrue(frames(value).all { (_, text) -> text.sumOf { if (it.code < 128) 1 else 2 } <= 8 })
    }

    @Test fun tooShortOrUnknownDurationsDoNotFlashThroughAllWindows() {
        for (end in listOf<Long?>(null, 0, 1_000)) {
            val value = plan(end = end)
            assertEquals(1, frames(value).size)
            assertEquals("一二三四五六", value.at(100_000).text)
        }
    }

    @Test fun limitedTimeKeepsFirstAndLastWithoutBreakingTheReadingMinimum() {
        val all = frames(plan(end = 4_000))
        assertEquals(2, all.size)
        assertEquals("一二三四五六", all.first().second)
        assertTrue(all.last().second.endsWith("甲乙"))
        assertTrue(all.last().first >= 1_500)
        assertTrue(4_000 - all.last().first >= 1_500)
    }

    @Test fun graphemeClustersAreNeverCutIntoSurrogatesAccentsOrEmojiPieces() {
        val family = "👨‍👩‍👧‍👦"
        val accent = "e\u0301"
        val original = family + accent + "甲乙丙"
        val regex = Pattern.compile("\\X")
        fun clusters(text: String): List<String> {
            val result = mutableListOf<String>()
            val matcher = regex.matcher(text)
            while (matcher.find()) result += matcher.group()
            return result
        }
        val value = SystemLyricWindow.create(SystemLyricInterval(original, 0, 20_000), 2f) { clusters(it).size.toFloat() }
        val allowed = clusters(original)
        assertTrue(frames(value).flatMap { clusters(it.second) }.all { it in allowed })
        assertTrue(value.at(0).text.startsWith(family + accent))
    }

    @Test fun wordBoundariesArePreferredWhenAWordFitsTheViewport() {
        val all = frames(plan("hi abcdef xyz", width = 7f))
        assertEquals("hi", all.first().second)
        assertTrue(all.any { it.second == "abcdef" })
        assertTrue(all.last().second.endsWith("xyz"))
    }

    @Test fun seekAndPausedProjectionUseMediaTimeRatherThanAccumulatedTimers() {
        val value = plan()
        val paused = SystemPlaybackPresentation(SystemPlaybackKey("opaque", 1), "歌名", "歌手", null,
            emptyList(), 3_000, 10_000, 12_000, false)
        assertEquals(value.at(3_000), value.at(paused.positionAt(100_000)))
        assertEquals("一二三四五六", value.at(paused.copy(positionMs = 0).positionAt(100_000)).text)
    }

    @Test fun repeatedTextStartsANewIntervalAndLastLineUsesActualDuration() {
        val snapshot = SystemPlaybackPresentation(SystemPlaybackKey("opaque", 1), "歌名", "歌手", null,
            listOf(SystemLyricLine(0, "同一句"), SystemLyricLine(5_000, "同一句")),
            5_100, 10_000, 8_000, false)
        assertEquals(SystemLyricInterval("同一句", 5_000, 8_000), snapshot.lyricIntervalAt(10_000))
        assertNull(snapshot.copy(durationMs = 0).lyricIntervalAt(10_000)?.endMs)
    }
}
