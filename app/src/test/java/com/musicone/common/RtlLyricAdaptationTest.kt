package com.musicone

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class RtlLyricAdaptationTest {
    @Test
    fun persianZwnjAndArabicDiacriticsStayInsideSingleScriptRun() {
        val persian = "می\u200Cخواهم تو را ببینم"
        val persianRuns = musicOneScriptRuns(persian, musicOneScriptOf(persian))
        assertEquals(1, persianRuns.size)
        assertEquals(MusicOneScript.ARABIC, persianRuns[0].script)

        val arabic = "مُحَمَّدٌ رَسُولُ"
        val arabicRuns = musicOneScriptRuns(arabic, musicOneScriptOf(arabic))
        assertEquals(1, arabicRuns.size)
        assertEquals(MusicOneScript.ARABIC, arabicRuns[0].script)
    }

    @Test
    fun arabicAndPersianFontsAndLocalesRouteCorrectlyAcrossModes() {
        val persian = "می\u200Cخواهم"
        val arabic = "أهلاً بك"

        val serifPersian = musicOneDisplayAnnotatedString(persian, mode = AppFontMode.DEFAULT)
        assertTrue(serifPersian.spanStyles.all {
            it.item.fontFamily == MusicOneDisplayFontFamilies.arabic && it.item.localeList == LocaleList("fa")
        })

        val sansArabic = musicOneDisplayAnnotatedString(arabic, mode = AppFontMode.SANS_SERIF)
        assertTrue(sansArabic.spanStyles.all {
            it.item.fontFamily == MusicOneUiFontFamilies.arabic && it.item.localeList == LocaleList("ar")
        })

        val systemPersian = musicOneDisplayAnnotatedString(persian, mode = AppFontMode.SYSTEM)
        assertTrue(systemPersian.spanStyles.all { it.item.fontFamily == null && it.item.localeList == LocaleList("fa") })
    }

    @Test
    fun originalTypographyDoesNotShrinkPersianOrStealLeadingArabicDiacritics() {
        val mixed = "مُحَمَّدٌ Hello ۲۰۲۴"
        val shown = lyricOriginalAnnotatedString(mixed, mode = AppFontMode.DEFAULT)
        val scaled = shown.spanStyles.filter { it.item.fontSize == .82.em }
        assertTrue(scaled.isNotEmpty())
        val diacriticIndex = mixed.indexOf('\u064C') // ٌ
        assertFalse("阿拉伯元音符号不能被划进西文缩字范围", scaled.any { diacriticIndex in it.start until it.end })
        val helloStart = mixed.indexOf("Hello")
        assertTrue(scaled.any { it.start <= helloStart && it.end >= helloStart + 5 })
        val persianDigitIndex = mixed.indexOf('۲')
        assertFalse("波斯数字不按西文缩小", scaled.any { persianDigitIndex in it.start until it.end })

        val purePersian = "در دل شب ستاره\u200Cها می\u200Cدرخشند"
        assertTrue(lyricOriginalAnnotatedString(purePersian).spanStyles.all { it.item.fontSize == TextUnit.Unspecified })
    }

    @Test
    fun trackPrimaryDirectionAlignsRightWithoutFlippingTranslations() {
        val persianSong = listOf(
            TimedLyric(0L, "در دل شب", translation = "在夜的深处，星光闪烁。"),
            TimedLyric(2000L, "می\u200Cخواهم بمانم", translation = "I want to stay."),
            TimedLyric(4000L, "Stay with me", translation = "留在我身边。"),
        )
        assertTrue(isRtlPrimaryLyrics(persianSong))
        assertEquals(TextDirection.Rtl, contentTextDirection(persianSong[0].text))
        assertEquals("中文翻译必须维持 LTR 阅读方向", TextDirection.Ltr, contentTextDirection(persianSong[0].translation!!))
        assertEquals("英文翻译必须维持 LTR 阅读方向", TextDirection.Ltr, contentTextDirection(persianSong[1].translation!!))
        assertEquals("波斯歌里的英文副歌保持 LTR", TextDirection.Ltr, contentTextDirection(persianSong[2].text))

        val chineseWithOneArabic = listOf(
            TimedLyric(0L, "夜风吹过街角"),
            TimedLyric(2000L, "灯火慢慢亮起"),
            TimedLyric(4000L, "سلام"),
        )
        assertFalse(isRtlPrimaryLyrics(chineseWithOneArabic))
        assertFalse(isRtlPrimaryLyrics(listOf(TimedLyric(0L, "暂无歌词"))))
    }

    @Test
    fun arabicPunctuationAttachesToPrecedingWordAndDiacriticsStayWhole() {
        val line = TimedLyric(
            timeMs = 0L,
            text = "سلام، چطوری؟",
            words = listOf(
                LyricWord(0L, 400L, "سلام"),
                LyricWord(400L, 900L, "چطوری"),
            ),
        )
        val ranges = lyricWordHighlightRanges(line)
        assertEquals(2, ranges.size)
        assertEquals("سلام، ", line.text.substring(ranges[0].start, ranges[0].end))
        assertEquals("چطوری؟", line.text.substring(ranges[1].start, ranges[1].end))

        // 即使原始词切片在元音符之前断开，边界保护也会保留完整字母+音标。
        val diacriticLine = TimedLyric(
            timeMs = 0L,
            text = "قَلب",
            words = listOf(LyricWord(0L, 500L, "ق"), LyricWord(500L, 1000L, "لب")),
        )
        val diacriticRanges = lyricWordHighlightRanges(diacriticLine)
        assertEquals("قَ", diacriticLine.text.substring(diacriticRanges[0].start, diacriticRanges[0].end))
        assertEquals("لب", diacriticLine.text.substring(diacriticRanges[1].start, diacriticRanges[1].end))
    }

    @Test
    fun desktopLyricsSupportRtlUnitsScrollTitleAndBidiIsolation() {
        assertEquals(2, desktopLyricUnits("می\u200Cخواهم بمانم"))
        assertEquals(2, desktopLyricUnits("مُحَمَّدٌ رَسُولُ"))

        // 超长 RTL 整行：从 overflow(200) 向 0 滚。
        assertEquals(200f, desktopLyricScroll(300f, 100f, null, 0, 1_000, 150, leftToRight = false), .01f)
        assertEquals(100f, desktopLyricScroll(300f, 100f, null, 0, 1_000, 500, leftToRight = false), .01f)
        assertEquals(0f, desktopLyricScroll(300f, 100f, null, 0, 1_000, 900, leftToRight = false), .01f)

        // 超长 RTL 逐字：edge 从 300 往 0 走，停在视口左侧 40%（右侧 60%）。
        assertEquals(200f, desktopLyricScroll(300f, 100f, 280f, 0, 1_000, 500, leftToRight = false), .01f)
        assertEquals(110f, desktopLyricScroll(300f, 100f, 150f, 0, 1_000, 500, leftToRight = false), .01f)
        assertEquals(0f, desktopLyricScroll(300f, 100f, 20f, 0, 1_000, 500, leftToRight = false), .01f)

        // 过长 RTL 歌名：起手停在右端 overflow，再向左滚到 0。
        assertEquals(DesktopTitleScroll(60f, 2_000), desktopTitleScroll(60f, 0, 1f, leftToRight = false))
        assertEquals(DesktopTitleScroll(30f, 0), desktopTitleScroll(60f, 3_000, 1f, leftToRight = false))
        assertEquals(DesktopTitleScroll(0f, 1_000), desktopTitleScroll(60f, 5_000, 1f, leftToRight = false))

        // RTL 逐字边缘：从 rowWidth(40) 推进到 0。
        val ranges = listOf(
            LyricHighlightRange(0, 2, LyricWord(0, 100, "شب")),
            LyricHighlightRange(2, 4, LyricWord(200, 300, "نو")),
        )
        val spans = listOf(40f to 20f, 20f to 0f)
        fun rtlEdge(at: Long) = desktopSungEdge(ranges, at, leftToRight = false, rowWidth = 40f) { s, _ ->
            if (s == 0) spans[0] else spans[1]
        }
        assertEquals(40f, rtlEdge(-1), .01f)
        assertEquals(30f, rtlEdge(50), .01f)
        assertEquals(20f, rtlEdge(150), .01f)
        assertEquals(10f, rtlEdge(250), .01f)
        assertEquals(0f, rtlEdge(300), .01f)

        // 桌面歌词歌名与歌手混排隔离，纯中文不受影响。
        val mixedTitle = joinBidiIsolated(listOf("شادمهر عقیلی", "Live"), " - ")
        assertEquals("${BIDI_FSI}شادمهر عقیلی${BIDI_PDI} - ${BIDI_FSI}Live${BIDI_PDI}", mixedTitle)
        assertEquals("歌名 - 歌手", joinBidiIsolated(listOf("歌名", "歌手"), " - "))
    }

    @Test
    fun playerTitleAndArtistsHandleBidiControlsAndMixedSeparators() {
        assertEquals("دریا", playerPrimaryTitle("\u200Fدریا (Live)\u200F"))
        assertEquals(
            "${BIDI_FSI}شادمهر عقیلی${BIDI_PDI} / ${BIDI_FSI}Arash${BIDI_PDI}",
            playerPrimaryArtists("شادمهر عقیلی / Arash"),
        )
    }

    @Test
    fun systemLyricWindowAndLocalParsersKeepPersianWordsAndZwnjIntact() {
        val text = "می\u200Cخواهم بمانم در این شب آرام"
        val window = SystemLyricWindow.create(SystemLyricInterval(text, 0L, 8_000L), 14f) { it.length.toFloat() }
        val firstFrame = window.at(0L).text
        assertTrue("首段不应把波斯连写词拦腰截断：$firstFrame", firstFrame.contains("می\u200Cخواهم"))

        val lrc = "[00:01.00]<00:01.00>می\u200Cخواهم <00:01.50>بمانم<00:02.00>"
        val parsed = parseLocalLrc(lrc, "lrc")
        assertEquals(1, parsed.size)
        assertEquals("می\u200Cخواهم بمانم", parsed[0].text)
        assertEquals(2, parsed[0].words.size)
        assertEquals("می\u200Cخواهم ", parsed[0].words[0].text)
    }

    @Test
    fun expandedArabicWordClipPathUsesSingleClockwiseRectWithoutWindingCancellation() {
        val rawBounds = Rect(120f, 0f, 260f, 80f)
        val clipBounds = lyricWordClipBounds(
            rawBounds = rawBounds,
            expandLeft = false,
            expandTop = true,
            expandRight = true,
            expandBottom = true,
            overhang = 18f,
        )
        assertEquals(Rect(120f, -18f, 278f, 98f), clipBounds)

        val addedPaths = mutableListOf<Path>()
        val addedRects = mutableListOf<Pair<Rect, Path.Direction>>()
        fun recordingPath(): Path = Proxy.newProxyInstance(
            Path::class.java.classLoader,
            arrayOf(Path::class.java),
        ) { _, method, args ->
            when (method.name) {
                "addPath" -> addedPaths.add(args!![0] as Path)
                "addRect" -> {
                    val rect = args!![0] as Rect
                    val direction = if (args.size >= 2) args[1] as Path.Direction else Path.Direction.CounterClockwise
                    addedRects.add(rect to direction)
                }
            }
            null
        } as Path

        val rawPath = recordingPath()
        val unchanged = lyricWordClipPath(rawPath, rawBounds, rawBounds, ::recordingPath)
        assertSame("未扩充裁剪框时直接复用原路径", rawPath, unchanged)

        val expanded = lyricWordClipPath(rawPath, rawBounds, clipBounds, ::recordingPath)
        assertTrue("扩充路径不得再叠加原顺时针 rawPath，防止 NonZero 绕数抵消镂空", addedPaths.isEmpty())
        assertEquals(listOf(clipBounds to Path.Direction.Clockwise), addedRects)
        assertTrue(expanded !== rawPath)
    }
}
