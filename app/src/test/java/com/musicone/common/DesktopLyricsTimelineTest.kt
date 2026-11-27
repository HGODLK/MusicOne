package com.musicone

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopLyricsTimelineTest {
    private val lines = listOf(SystemLyricLine(1_000, "第一句"), SystemLyricLine(4_000, " "), SystemLyricLine(6_000, "第二句"))

    private fun snapshot(lyrics: List<SystemLyricLine> = lines, state: LyricLoadState = LyricLoadState.READY,
        id: String = "a", ready: Boolean = true) = SystemPlaybackPresentation(
        key = SystemPlaybackKey(id, 1L), title = "歌名", artist = "歌手", artworkUri = null, lyrics = lyrics,
        positionMs = 0L, sampledAtMs = 0L, durationMs = 60_000L, advancing = true,
        readyForPresentation = ready, lyricState = state)

    @Test
    fun introShowsTitleAndBlankInterludeKeepsPreviousLine() {
        val resolver = DesktopLyricResolver()
        assertEquals("歌名 - 歌手", resolver.resolve(snapshot(), 500, 0).text)
        assertEquals("第一句", resolver.resolve(snapshot(), 1_000, 0).text)
        assertEquals(0, resolver.resolve(snapshot(), 5_000, 0).key.index)
        assertEquals("第二句", resolver.resolve(snapshot(), 6_000, 0).text)
        assertEquals(12_000L, resolver.resolve(snapshot(), 6_000, 0).endMs)
    }

    @Test
    fun loadingWaitsBeforeEmptyAndRetryDoesNotFlashBack() {
        val resolver = DesktopLyricResolver(3_000)
        val loading = snapshot(emptyList(), LyricLoadState.LOADING)
        assertEquals(DesktopLyricKind.TITLE, resolver.resolve(loading, 0, 10_000).key.kind)
        assertEquals(13_000L, resolver.loadingDeadlineMs)
        assertEquals(DESKTOP_NO_LYRICS, resolver.resolve(loading, 0, 13_000).text)
        assertNull(resolver.loadingDeadlineMs)
        assertEquals(DesktopLyricKind.LINE, resolver.resolve(snapshot(), 1_500, 14_000).key.kind)
        val failed = snapshot(emptyList(), LyricLoadState.RETRYABLE, "b")
        assertEquals(DESKTOP_NO_LYRICS, resolver.resolve(failed, 0, 15_000).text)
        assertEquals("重试期间不闪回歌名", DESKTOP_NO_LYRICS,
            resolver.resolve(snapshot(emptyList(), LyricLoadState.LOADING, "b"), 0, 15_100).text)
        assertEquals("换歌重新等待", DesktopLyricKind.TITLE,
            resolver.resolve(snapshot(emptyList(), LyricLoadState.LOADING, "c"), 0, 15_200).key.kind)
    }

    @Test
    fun trackNotReadyShowsTitleEvenWithLyrics() {
        assertEquals(DesktopLyricKind.TITLE, DesktopLyricResolver().resolve(snapshot(ready = false), 6_500, 0).key.kind)
    }

    @Test
    fun lastLineEndFollowsShiftedWordTime() {
        val source = TimedLyric(1_000, "词", words = listOf(LyricWord(1_000, 3_000, "词")))
        val shifted = snapshot(listOf(SystemLyricLine(1_500, "词", source = source)))
        assertEquals(3_500L, DesktopLyricResolver().resolve(shifted, 2_000, 0).endMs)
    }

    @Test
    fun longInterludeDoesNotSlowScroll() {
        // 每字约 400ms 的歌，第三句之后有 30 秒间奏。
        val song = (0L..5L).map { SystemLyricLine(it * 4_000 + if (it >= 3) 30_000 else 0, "一二三四五六七八九十") }
        assertEquals(500L, desktopLyricUnitMs(song))
        val content = DesktopLyricResolver().resolve(snapshot(song), 8_000, 0)
        assertEquals(2, content.key.index)
        assertEquals(42_000L, content.endMs)
        assertEquals(13_000L, content.scrollEndMs)
        assertEquals("正常句按实际句长", 4_000L, DesktopLyricResolver().resolve(snapshot(song), 0, 0).scrollEndMs)
    }

    @Test
    fun wordLineScrollEndsWithLastWord() {
        val source = TimedLyric(1_000, "词", words = listOf(LyricWord(1_000, 3_000, "词")))
        val song = listOf(SystemLyricLine(1_000, "词", source = source), SystemLyricLine(20_000, "下一句"))
        assertEquals(3_000L, DesktopLyricResolver().resolve(snapshot(song), 2_000, 0).scrollEndMs)
    }

    @Test
    fun unitsCountCjkCharactersAndLatinWords() {
        assertEquals(4, desktopLyricUnits("你好，世界"))
        assertEquals(3, desktopLyricUnits("Don't stop me"))
        assertEquals(3, desktopLyricUnits("愛 love 你"))
        assertEquals(0, desktopLyricUnits(" ♪ "))
    }

    @Test
    fun longTitleHoldsAtBothEndsAndReturns() {
        assertEquals(DesktopTitleScroll(0f, 2_000), desktopTitleScroll(60f, 0, 1f))
        assertEquals(DesktopTitleScroll(30f, 0), desktopTitleScroll(60f, 3_000, 1f))
        assertEquals(DesktopTitleScroll(60f, 1_000), desktopTitleScroll(60f, 5_000, 1f))
        assertEquals(DesktopTitleScroll(30f, 0), desktopTitleScroll(60f, 7_000, 1f))
        assertEquals(DesktopTitleScroll(0f, 2_000), desktopTitleScroll(60f, 8_000, 1f))
    }

    @Test
    fun accentFollowsSourceAndStaysReadable() {
        assertEquals("未声明用白色", -1, desktopLyricsAccent(null))
        assertEquals("透明视为未声明", -1, desktopLyricsAccent(0L))
        for (color in listOf(0xFFEC4141L, 0xFF31C27CL, 0xFF009AF3L, 0xFF9B8AC4L, 0xFF000000L, 0xFF0000FFL))
            assertTrue(desktopColorLuminance(desktopLyricsAccent(color)) >= .36)
        assertEquals("亮度足够时只掺四分之一白", 0xFF64D19C.toInt(), desktopLyricsAccent(0xFF31C27CL))
    }

    @Test
    fun clockIgnoresJitterButFollowsSeekAndPause() {
        val clock = DesktopLyricClock()
        val key = SystemPlaybackKey("a", 1L)
        clock.rebase(key, 1_000, true, 0)
        assertEquals(1_500L, clock.position(500))
        clock.rebase(key, 1_300, true, 500)
        assertEquals("小幅回退不倒退", 1_500L, clock.position(500))
        clock.rebase(key, 200, true, 500)
        assertEquals("回拖立即跟随", 200L, clock.position(500))
        clock.freeze(700)
        assertEquals(400L, clock.position(9_000))
        clock.rebase(SystemPlaybackKey("a", 2L), 300, false, 9_000)
        assertEquals("新代次不按抖动处理", 300L, clock.position(9_500))
    }

    @Test
    fun longLineScrollStaysInsideText() {
        assertEquals(0f, desktopLyricScroll(80f, 100f, 80f, 0, 1_000, 500), 0f)
        assertEquals(0f, desktopLyricScroll(300f, 100f, 40f, 0, 1_000, 500), 0f)
        assertEquals(90f, desktopLyricScroll(300f, 100f, 150f, 0, 1_000, 500), .01f)
        assertEquals(200f, desktopLyricScroll(300f, 100f, 300f, 0, 1_000, 500), 0f)
        assertEquals(0f, desktopLyricScroll(300f, 100f, null, 0, 1_000, 150), .01f)
        assertEquals(100f, desktopLyricScroll(300f, 100f, null, 0, 1_000, 500), .01f)
        assertEquals(200f, desktopLyricScroll(300f, 100f, null, 0, 1_000, 900), 0f)
    }

    @Test
    fun sungEdgeInterpolatesInsideWordAndSkipsPunctuation() {
        val ranges = listOf(LyricHighlightRange(0, 1, LyricWord(0, 100, "甲")), LyricHighlightRange(1, 2, null),
            LyricHighlightRange(2, 3, LyricWord(200, 300, "乙")), LyricHighlightRange(3, 4, null))
        fun edge(at: Long) = desktopSungEdge(ranges, at) { it * 10f }
        assertEquals(0f, edge(-1), 0f)
        assertEquals(5f, edge(50), .01f)
        assertEquals(10f, edge(150), .01f)
        assertEquals(25f, edge(250), .01f)
        assertEquals(40f, edge(300), 0f)
    }

    @Test
    fun transitionDirectionFollowsJump() {
        fun key(index: Int, id: String = "a") = DesktopLyricKey(id, DesktopLyricKind.LINE, index)
        assertEquals(DesktopLyricTransition.FORWARD, desktopLyricTransition(key(1), key(2), false))
        assertEquals(DesktopLyricTransition.BACKWARD, desktopLyricTransition(key(2), key(1), false))
        assertEquals(DesktopLyricTransition.FADE, desktopLyricTransition(key(2), key(9), false))
        assertEquals(DesktopLyricTransition.FORWARD, desktopLyricTransition(key(9), key(0, "b"), false))
        assertEquals(DesktopLyricTransition.FADE, desktopLyricTransition(key(1), key(2), true))
        assertEquals(DesktopLyricTransition.FADE, desktopLyricTransition(null, key(0), false))
    }

    @Test
    fun positionSurvivesRotationAndStaysInSafeArea() {
        assertEquals(350, desktopLyricsOrigin(.5f, 100, 900, 300))
        assertEquals("放不下时贴住安全区起点", 100, desktopLyricsOrigin(.8f, 100, 300, 400))
        assertEquals(.5f, desktopLyricsFraction(350, 100, 900, 300, 0f), .001f)
        assertEquals("没有余地时保留原比例", .3f, desktopLyricsFraction(100, 100, 300, 400, .3f), 0f)
        assertEquals(336f, desktopLyricsWidthDp(360f), 0f)
        assertEquals(520f, desktopLyricsWidthDp(1_280f), 0f)
    }

    @Test
    fun sideDragResizesOnlyWhenHorizontal() {
        assertEquals(DesktopLyricsGesture.RESIZE_LEFT, desktopLyricsGesture(20f, 300f, 12f, 3f))
        assertEquals(DesktopLyricsGesture.RESIZE_RIGHT, desktopLyricsGesture(280f, 300f, -12f, 3f))
        assertEquals(DesktopLyricsGesture.MOVE, desktopLyricsGesture(150f, 300f, 12f, 3f))
        assertEquals("两侧竖向起手仍是移动", DesktopLyricsGesture.MOVE, desktopLyricsGesture(20f, 300f, 3f, 12f))
    }

    @Test
    fun resizeKeepsOppositeSideAndLimits() {
        fun resize(gesture: DesktopLyricsGesture, dx: Float) = desktopLyricsResize(gesture, 100f, 500f, dx, 20f, 600f, 160f)
        assertEquals(150f to 500f, resize(DesktopLyricsGesture.RESIZE_LEFT, 50f))
        assertEquals("不小于最窄", 340f to 500f, resize(DesktopLyricsGesture.RESIZE_LEFT, 400f))
        assertEquals("不越过可用区域", 20f to 500f, resize(DesktopLyricsGesture.RESIZE_LEFT, -400f))
        assertEquals(100f to 450f, resize(DesktopLyricsGesture.RESIZE_RIGHT, -50f))
        assertEquals(100f to 260f, resize(DesktopLyricsGesture.RESIZE_RIGHT, -400f))
        assertEquals(100f to 600f, resize(DesktopLyricsGesture.RESIZE_RIGHT, 400f))
    }

    @Test
    fun alignPlacesFittingRowsInsideInset() {
        assertEquals(5f, DesktopLyricsAlign.LEFT.rowStart(300f, 100f, 5f), 0f)
        assertEquals(100f, DesktopLyricsAlign.CENTER.rowStart(300f, 100f, 5f), 0f)
        assertEquals(195f, DesktopLyricsAlign.RIGHT.rowStart(300f, 100f, 5f), 0f)
        // 刚好放满时三种对齐位置一致。
        DesktopLyricsAlign.entries.forEach { assertEquals(5f, it.rowStart(300f, 290f, 5f), 0f) }
        assertEquals(DesktopLyricsAlign.CENTER, DesktopLyricsAlign.fromStored(null))
        assertEquals("重置位置不改对齐", DesktopLyricsAlign.RIGHT,
            DesktopLyricsConfig(align = DesktopLyricsAlign.RIGHT).withDefaultPlacement().align)
    }

    @Test
    fun savedWidthIsSeparateForNarrowAndWideScreens() {
        val config = DesktopLyricsConfig().withWidthDp(360f, 200f).withWidthDp(1_280f, 900f)
        assertEquals(200f, desktopLyricsWidthDp(360f, config.widthDp(360f)), 0f)
        assertEquals("可超过默认上限", 900f, desktopLyricsWidthDp(1_280f, config.widthDp(1_280f)), 0f)
        assertEquals("分屏变窄时收回可用宽度", 700f, desktopLyricsWidthDp(700f, config.widthDp(700f)), 0f)
        assertEquals(336f, desktopLyricsWidthDp(360f, config.withDefaultPlacement().widthDp(360f)), 0f)
    }

    @Test
    fun sharedPresentationsDeliverUpstreamFailureToSubscriber() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val shared = flow<SystemPlaybackPresentation?> { error("上游失败") }.sharedSystemPresentations(scope)
            assertTrue(runCatching { shared.first() }.exceptionOrNull() is IllegalStateException)
        } finally { scope.cancel() }
    }
}
