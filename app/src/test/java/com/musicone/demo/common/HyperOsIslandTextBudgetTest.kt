package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test
import java.util.regex.Pattern

class HyperOsIslandTextBudgetTest {
    private fun measure(text: String, scale: Float = 1f): Float {
        val matcher = Pattern.compile("\\X").matcher(text)
        var width = 0f
        while (matcher.find()) width += (if (matcher.group() == " ") 4f else 14f) * scale
        return width
    }

    @Test fun longTitleArtistAndLyricCannotConsumeTheReservedStatusAreas() {
        for ((w, h) in listOf(320 to 720, 360 to 800, 393 to 852, 800 to 1280, 1280 to 800)) {
            val layout = HyperOsIslandTextBudget(w, h)
            val value = layout.summary("说好不哭 with 五月天阿信", "周杰伦和阿信", "长歌词不能撑满状态栏",
                1f, ::measure)
            val leftWidth = measure(value.title) + if (value.artist.isBlank()) 0f else measure(" " + value.artist)
            assertTrue(leftWidth <= layout.metadataWidthDp)
            assertTrue(measure(value.lyric) <= layout.lyricWidthDp)
            assertTrue(leftWidth + measure(value.lyric) + 64f + 160f <= minOf(w, h))
            assertTrue(value.title.endsWith("…"))
        }
    }

    @Test fun fontScalingReducesTextInsteadOfGrowingTheIslandBudget() {
        val layout = HyperOsIslandTextBudget(360, 800)
        val normal = layout.summary("长长长长歌名", "歌手", "长长长长长长歌词", 1f, ::measure)
        val large = layout.summary("长长长长歌名", "歌手", "长长长长长长歌词", 1f) { measure(it, 2f) }
        assertTrue(large.title.length < normal.title.length)
        assertTrue(large.lyric.length < normal.lyric.length)
        assertTrue(measure(large.lyric, 2f) <= layout.lyricWidthDp)
    }

    @Test fun artistOnlyUsesSpaceLeftByTheSongTitle() {
        val layout = HyperOsIslandTextBudget(360, 800)
        val value = layout.summary("歌", "歌手", "原文", 1f, ::measure)
        assertEquals("歌手", value.artist)
        val long = layout.summary("长长长长长", "歌手", "原文", 1f, ::measure)
        assertEquals("", long.artist)
    }

    @Test fun landscapeAndTabletDoNotAllocateLongerLines() {
        assertEquals(HyperOsIslandTextBudget(360, 800).lyricWidthDp,
            HyperOsIslandTextBudget(800, 360).lyricWidthDp)
        assertEquals(HyperOsIslandTextBudget(393, 852).lyricWidthDp,
            HyperOsIslandTextBudget(1280, 800).lyricWidthDp)
    }

    @Test fun ellipsisNeverSplitsJoinedEmojiOrCombiningCharacters() {
        for (unit in listOf("👨‍👩‍👧‍👦", "e\u0301", "🎵")) {
            assertEquals(unit + "…", HyperOsIslandTextBudget.fit(unit.repeat(5), 28f, ::measure))
            assertEquals("", HyperOsIslandTextBudget.fit(unit.repeat(5), 10f, ::measure))
        }
    }

    @Test fun fallbackAndMultilineTextAreAlsoBounded() {
        assertEquals("歌 名", HyperOsIslandTextBudget.fit(" 歌\n名 ", 56f, ::measure))
        assertEquals("歌名歌…", HyperOsIslandTextBudget.fit("歌名歌名歌名", 56f, ::measure))
    }

    @Test fun segmentedLyricStillFollowsPlaybackTimeWithinTheSmallerBudget() {
        val width = HyperOsIslandTextBudget(320, 720).lyricWidthDp
        val line = SystemLyricInterval("甲乙丙丁戊己庚辛壬癸", 0, 12_000)
        val plan = SystemLyricWindow.create(line, width, ::measure)
        assertNotEquals(plan.at(0).text, plan.at(11_999).text)
        for (time in 0L..11_999L step 100L) assertTrue(measure(plan.at(time).text) <= width)
        assertEquals(plan.at(0).text, plan.at(500).text)
        assertTrue(plan.at(11_999).text.endsWith("癸"))
    }

    @Test fun extraLyricSpaceNeverComesFromReservedStatusAreasOrTitleBudget() {
        val narrow = HyperOsIslandTextBudget(320, 720)
        val normal = HyperOsIslandTextBudget(360, 800)
        val tablet = HyperOsIslandTextBudget(800, 1280)
        assertEquals(48f, narrow.lyricWidthDp, 0f)
        assertEquals(56f, normal.metadataWidthDp, 0f)
        assertEquals(80f, normal.lyricWidthDp, 0f)
        assertEquals(80f, tablet.lyricWidthDp, 0f)
        assertTrue(normal.lyricWidthDp < (360 * 0.30f).coerceIn(80f, 160f) * 0.85f)
        val value = normal.summary("歌名", "歌手", "一二三四五六七八", 3f) { measure(it, 3f) }
        assertTrue(measure(value.lyric, 3f) <= normal.lyricWidthDp * 3f)
    }
}
