package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class LyricWordEmphasisTest {

    @Test
    fun regressionNiHaoShiJieAttachesPunctuationToPrecedingWords() {
        val line = TimedLyric(
            timeMs = 0L,
            text = "你好，世界。",
            words = listOf(
                LyricWord(0, 500, "你好"),
                LyricWord(500, 1000, "世界"),
            ),
        )
        val ranges = lyricWordHighlightRanges(line)
        assertEquals(2, ranges.size)
        // “，”跟随“你好”
        assertEquals(0, ranges[0].start)
        assertEquals(3, ranges[0].end)
        assertEquals("你好，", line.text.substring(ranges[0].start, ranges[0].end))
        assertEquals(line.words[0], ranges[0].word)

        // “。”跟随“世界”
        assertEquals(3, ranges[1].start)
        assertEquals(6, ranges[1].end)
        assertEquals("世界。", line.text.substring(ranges[1].start, ranges[1].end))
        assertEquals(line.words[1], ranges[1].word)
    }

    @Test
    fun regressionXiangQianZhouQuotesAndExclamationAttachment() {
        val line = TimedLyric(
            timeMs = 1000L,
            text = "“向前走”，别回头！",
            words = listOf(
                LyricWord(1000, 1500, "向前走"),
                LyricWord(1500, 2000, "别回头"),
            ),
        )
        val ranges = lyricWordHighlightRanges(line)
        assertEquals(2, ranges.size)
        // ““”（前引号）向后跟随“向前走”，“”，”（闭引号与逗号）向前跟随“向前走”
        assertEquals(0, ranges[0].start)
        assertEquals(6, ranges[0].end)
        assertEquals("“向前走”，", line.text.substring(ranges[0].start, ranges[0].end))
        assertEquals(line.words[0], ranges[0].word)

        // “！”向前跟随“别回头”
        assertEquals(6, ranges[1].start)
        assertEquals(10, ranges[1].end)
        assertEquals("别回头！", line.text.substring(ranges[1].start, ranges[1].end))
        assertEquals(line.words[1], ranges[1].word)
    }

    @Test
    fun regressionGuangBracketsAndCommaAttachment() {
        val line = TimedLyric(
            timeMs = 2000L,
            text = "光（照进来），梦还在。",
            words = listOf(
                LyricWord(2000, 2300, "光"),
                LyricWord(2300, 2800, "照进来"),
                LyricWord(2800, 3400, "梦还在"),
            ),
        )
        val ranges = lyricWordHighlightRanges(line)
        assertEquals(3, ranges.size)

        assertEquals(0, ranges[0].start)
        assertEquals(1, ranges[0].end)
        assertEquals("光", line.text.substring(ranges[0].start, ranges[0].end))
        assertEquals(line.words[0], ranges[0].word)

        // “（”向后跟随“照进来”，“），”向前跟随“照进来”
        assertEquals(1, ranges[1].start)
        assertEquals(7, ranges[1].end)
        assertEquals("（照进来），", line.text.substring(ranges[1].start, ranges[1].end))
        assertEquals(line.words[1], ranges[1].word)

        // “。”向前跟随“梦还在”
        assertEquals(7, ranges[2].start)
        assertEquals(11, ranges[2].end)
        assertEquals("梦还在。", line.text.substring(ranges[2].start, ranges[2].end))
        assertEquals(line.words[2], ranges[2].word)
    }

    @Test
    fun regressionHelloWorldEnglishAndPunctuation() {
        val line = TimedLyric(
            timeMs = 0L,
            text = "Hello, world!（继续）",
            words = listOf(
                LyricWord(0, 300, "Hello"),
                LyricWord(300, 600, "world"),
                LyricWord(600, 900, "继续"),
            ),
        )
        val ranges = lyricWordHighlightRanges(line)
        assertEquals(3, ranges.size)

        // ", " 跟随 Hello
        assertEquals(0, ranges[0].start)
        assertEquals(7, ranges[0].end)
        assertEquals("Hello, ", line.text.substring(ranges[0].start, ranges[0].end))
        assertEquals(line.words[0], ranges[0].word)

        // "!" 跟随 world
        assertEquals(7, ranges[1].start)
        assertEquals(13, ranges[1].end)
        assertEquals("world!", line.text.substring(ranges[1].start, ranges[1].end))
        assertEquals(line.words[1], ranges[1].word)

        // "（继续）" 跟随 继续
        assertEquals(13, ranges[2].start)
        assertEquals(17, ranges[2].end)
        assertEquals("（继续）", line.text.substring(ranges[2].start, ranges[2].end))
        assertEquals(line.words[2], ranges[2].word)
    }

    @Test
    fun regressionWangYangYuHaoMiaoBookTitleMarks() {
        val line = TimedLyric(
            timeMs = 0L,
            text = "《汪洋与浩渺》",
            words = listOf(
                LyricWord(0, 400, "汪洋"),
                LyricWord(400, 600, "与"),
                LyricWord(600, 1000, "浩渺"),
            ),
        )
        val ranges = lyricWordHighlightRanges(line)
        assertEquals(3, ranges.size)

        // 《 跟随 汪洋
        assertEquals(0, ranges[0].start)
        assertEquals(3, ranges[0].end)
        assertEquals("《汪洋", line.text.substring(ranges[0].start, ranges[0].end))

        assertEquals(3, ranges[1].start)
        assertEquals(4, ranges[1].end)
        assertEquals("与", line.text.substring(ranges[1].start, ranges[1].end))

        // 》 跟随 浩渺
        assertEquals(4, ranges[2].start)
        assertEquals(7, ranges[2].end)
        assertEquals("浩渺》", line.text.substring(ranges[2].start, ranges[2].end))

        // 验证全句首尾相接完整覆盖
        assertEquals(0, ranges.first().start)
        assertEquals(line.text.length, ranges.last().end)
        for (i in 0 until ranges.size - 1) {
            assertEquals(ranges[i].end, ranges[i + 1].start)
        }
    }

    @Test
    fun regressionLongWrappingLyricContinuousCoverage() {
        val text = "当星光落入苍茫的大海，远方的风吹向未知的港湾。"
        val words = listOf(
            LyricWord(0, 500, "当星光"),
            LyricWord(500, 1000, "落入"),
            LyricWord(1000, 1600, "苍茫的大海"),
            LyricWord(1600, 2100, "远方的风"),
            LyricWord(2100, 2600, "吹向"),
            LyricWord(2600, 3200, "未知的港湾"),
        )
        val line = TimedLyric(0L, text, words = words)
        val ranges = lyricWordHighlightRanges(line)

        assertEquals(0, ranges.first().start)
        assertEquals(text.length, ranges.last().end)
        for (i in 0 until ranges.size - 1) {
            assertEquals("相邻区间必须连续无缝", ranges[i].end, ranges[i + 1].start)
        }
    }

    @Test
    fun zeroDurationPunctuationDoesNotFlashAlone() {
        val words = listOf(
            LyricWord(0, 100, "光"),
            LyricWord(100, 200, "光"),
            LyricWord(200, 200, "。"), // 零时长标点
        )
        val line = TimedLyric(0, "光光。", words = words)
        val ranges = lyricWordHighlightRanges(line)

        // 零时长标点“。”应被归入第二个“光”，不单独产生零时长独立段
        assertEquals(2, ranges.size)
        assertEquals(0, ranges[0].start)
        assertEquals(1, ranges[0].end)
        assertEquals("光", line.text.substring(ranges[0].start, ranges[0].end))

        assertEquals(1, ranges[1].start)
        assertEquals(3, ranges[1].end)
        assertEquals("光。", line.text.substring(ranges[1].start, ranges[1].end))
    }

    @Test
    fun wordRangeStabilityAcrossPlaybackProgress() {
        val word = LyricWord(1000, 2000, "星光")
        val line = TimedLyric(1000, "星光", words = listOf(word))
        val ranges = lyricWordHighlightRanges(line)

        // 验证播放前、播放中、播放后范围完全一致（排版几何绝对恒定）
        val beforeAlpha = lyricHighlightAlpha(ranges[0].word, 500)
        val playingAlpha = lyricHighlightAlpha(ranges[0].word, 1500)
        val afterAlpha = lyricHighlightAlpha(ranges[0].word, 2500)

        assertEquals(0.7f, beforeAlpha, 0.0001f)
        assertEquals(0.85f, playingAlpha, 0.0001f)
        assertEquals(1.0f, afterAlpha, 0.0001f)

        // 字符范围保持稳定不变
        assertEquals(0, ranges[0].start)
        assertEquals(2, ranges[0].end)
    }

    @Test
    fun unassociatedPunctuationKeepsBaseBrightness() {
        val line = TimedLyric(0, "……", words = emptyList())
        val ranges = lyricWordHighlightRanges(line)
        assertEquals(1, ranges.size)
        assertNull(ranges[0].word)
        assertEquals(1.0f, lyricHighlightAlpha(ranges[0].word, 1000), 0.0001f)
    }
}
