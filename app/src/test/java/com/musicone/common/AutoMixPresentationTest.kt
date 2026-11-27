package com.musicone

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** 用假过渡验证自然过渡的界面约定：背景／封面进度、歌词保持、进度条位置。 */
class AutoMixPresentationTest {
    private val a = track("a", lyrics = listOf(TimedLyric(10_000, "上一首"), TimedLyric(200_000, "上一首末句")))
    private val b = track("b", lyrics = listOf(TimedLyric(0, "作词：某人"), TimedLyric(10_000, "切入点之前"),
        TimedLyric(30_000, "第一句")))
    private val plan = AutoMixPlan(a, b, AutoMixKind.BEAT_MATCH, enterMs = 2_000, seamMs = 8_000,
        fromEndMs = 12_000, totalMs = 14_000)

    @Before fun reset() {
        // 会话是进程级单例：用一次被打断的假过渡清掉上个用例留下的结束记录。
        AutoMixSession.begin(FakeTransition(plan))
        AutoMixSession.end(AutoMixEnd.INTERRUPTED)
        AutoMixLyricHold.resetForTest()
    }

    @After fun tearDown() {
        AutoMixSession.end(AutoMixEnd.INTERRUPTED)
        AutoMixLyricHold.resetForTest()
    }

    @Test fun visualProgressIsNormalizedToTheWindowWithoutExtraEasing() {
        val transition = FakeTransition(plan)
        val drive = AutoMixVisualDrive(transition, 0f)
        assertEquals(0f, drive.progress(0), 0f)
        assertEquals(0f, drive.progress(2_000), 0f)
        assertEquals(.5f, drive.progress(7_000), 1e-4f)
        assertEquals(1f, drive.progress(12_000), 0f)
        assertEquals(1f, drive.progress(13_000), 0f)
    }

    @Test fun atmosphereSweepLeadsFromTheBottomAndStaysInsideTheWindow() {
        val drive = AutoMixVisualDrive(FakeTransition(plan), AUTOMIX_ATMOSPHERE_STAGGER_FRACTION)
        val next = TrackTransitionDirection.NEXT
        // 进场前与 A 收干净后，各位置都分别停在两端。
        for (depth in listOf(0f, .5f, 1f)) {
            assertEquals(0f, drive.sweepProgress(2_000, depth, next), 0f)
            assertEquals(1f, drive.sweepProgress(12_000, depth, next), 0f)
        }
        // 途中下方（depth=1）领先上方。
        val bottom = drive.sweepProgress(6_000, 1f, next)
        val top = drive.sweepProgress(6_000, 0f, next)
        assertTrue(bottom > top)
        // 上方的起步滞后约窗口的 15%。
        assertEquals(0f, drive.sweepProgress(3_400, 0f, next), 0f)
        assertTrue(drive.sweepProgress(3_400, 1f, next) > 0f)
    }

    @Test fun reducedMotionDriveIsAUniformCrossfade() {
        val drive = AutoMixVisualDrive(FakeTransition(plan), 0f)
        assertEquals(drive.sweepProgress(6_000, 0f, TrackTransitionDirection.NEXT),
            drive.sweepProgress(6_000, 1f, TrackTransitionDirection.NEXT), 0f)
    }

    @Test fun drivenSweepResumesFromTheColorsVisibleWhenItTookOver() {
        val transition = FakeTransition(plan, clock = 7_000)
        val drive = AutoMixVisualDrive(transition, 0f)
        val sweep = AtmosphereSweep(intArrayOf(0), androidx.compose.ui.graphics.Color.Black,
            androidx.compose.ui.graphics.Color.Black, intArrayOf(-1), TrackTransitionDirection.NEXT, settled = false,
            drive = drive)
        sweep.driveClock.longValue = 7_000
        assertEquals(0f, sweep.progressAt(.5f), 0f)
        sweep.driveClock.longValue = 12_000
        assertEquals(1f, sweep.progressAt(.5f), 0f)
        val fresh = AtmosphereSweep(intArrayOf(0), androidx.compose.ui.graphics.Color.Black,
            androidx.compose.ui.graphics.Color.Black, intArrayOf(-1), TrackTransitionDirection.NEXT, settled = false,
            drive = drive, resumeFromStart = false)
        fresh.driveClock.longValue = 7_000
        assertEquals(.5f, fresh.progressAt(.5f), 1e-4f)
    }

    @Test fun lyricsStayOnThePreviousTrackUntilTheNextOneStartsSinging() {
        val transition = FakeTransition(plan, clock = 1_000, to = 16_000)
        AutoMixSession.begin(transition)
        // 交接前歌词本来就是上一首。
        assertNull(hold(a, 200_000))
        transition.clock = 9_000
        transition.to = 23_000
        assertSame(plan, hold(b, null))
        // 切入点（16 秒）之前的歌词不算开唱；第一句在 30 秒，提前量之内才放行。
        transition.to = 30_000 - AUTOMIX_LYRIC_HANDOFF_LEAD_MS - 1
        assertSame(plan, hold(b, null))
        transition.to = 30_000 - AUTOMIX_LYRIC_HANDOFF_LEAD_MS
        assertNull(hold(b, null))
        // 释放后同一次过渡不再保持。
        transition.to = 20_000
        assertNull(hold(b, null))
    }

    @Test fun holdContinuesAfterTheTransitionCompletesUntilTheFirstLine() {
        val transition = FakeTransition(plan, clock = 9_000, to = 20_000)
        AutoMixSession.begin(transition)
        assertSame(plan, hold(b, null))
        AutoMixSession.end(AutoMixEnd.COMPLETED)
        assertSame(plan, hold(b, 25_000))
        assertNull(hold(b, 29_700))
    }

    @Test fun nextTrackWithoutLyricsSwitchesWhenThePreviousOneIsGone() {
        val silent = b.copy(lyrics = emptyList())
        val transition = FakeTransition(plan.copy(to = silent), clock = 9_000, to = 20_000)
        AutoMixSession.begin(transition)
        assertNotNull(hold(silent, null, LyricLoadState.UNAVAILABLE))
        transition.clock = 12_000
        assertNull(hold(silent, null, LyricLoadState.UNAVAILABLE))
    }

    @Test fun interruptionReleasesTheHeldLyricsImmediately() {
        val transition = FakeTransition(plan, clock = 9_000, to = 20_000)
        AutoMixSession.begin(transition)
        assertNotNull(hold(b, null))
        AutoMixSession.end(AutoMixEnd.INTERRUPTED)
        assertNull(hold(b, 20_000))
    }

    @Test fun seekingBackAfterCompletionReleasesTheHold() {
        val transition = FakeTransition(plan, clock = 9_000, to = 20_000)
        AutoMixSession.begin(transition)
        assertNotNull(hold(b, null))
        AutoMixSession.end(AutoMixEnd.COMPLETED)
        assertNotNull(hold(b, 22_000))
        assertNull(hold(b, 5_000))
    }

    @Test fun manualSwitchAwayReleasesTheHold() {
        val transition = FakeTransition(plan, clock = 9_000, to = 20_000)
        AutoMixSession.begin(transition)
        assertNull(hold(track("c"), 0))
    }

    @Test fun systemLyricsFreezeOnThePreviousTrackWhileHeld() = runBlocking {
        val transition = FakeTransition(plan, clock = 1_000, to = 16_000)
        AutoMixSession.begin(transition)
        val upstream = MutableStateFlow<SystemPlaybackPresentation?>(presentation(a, 190_000))
        var held: AutoMixPlan? = null
        val seen = mutableListOf<SystemPlaybackPresentation?>()
        val job = launch {
            upstream.holdAutoMixLyrics({ held }, nowMs = { 1_000L }).collect { seen += it }
        }
        kotlinx.coroutines.delay(50)
        held = plan
        upstream.value = presentation(b, 20_000)
        kotlinx.coroutines.delay(50)
        assertEquals("a", seen.last()?.key?.trackId)
        assertFalse(seen.last()!!.advancing)
        held = null
        // 释放后下一次复查即放行下一首。
        kotlinx.coroutines.delay(250)
        assertEquals("b", seen.last()?.key?.trackId)
        job.cancel()
    }

    @Test fun timelineFollowsEachTracksOwnPositionDuringTheTransition() {
        val transition = FakeTransition(plan, clock = 5_000, to = 16_000, from = 238_000)
        AutoMixSession.begin(transition)
        val progress = PlaybackProgressSnapshot("a", 230_000)
        val before = playerTimelineSnapshot(MusicOneUiState(currentTrack = a), progress, PlaybackActivity("a", true), null)
        assertEquals(238_000, before.positionMs)
        // 交接时播放进度还没换成下一首，也不能显示 0。
        val after = playerTimelineSnapshot(MusicOneUiState(currentTrack = b), progress, PlaybackActivity("a", true), null)
        assertEquals(16_000, after.positionMs)
        assertTrue(after.advancing)
        assertTrue(autoMixProgressHandoff("a", "b"))
        assertFalse(autoMixProgressHandoff("b", "a"))
    }

    @Test fun nothingChangesWithoutATransition() {
        assertNull(autoMixTimelinePosition(a))
        assertFalse(autoMixProgressHandoff("a", "b"))
        assertNull(autoMixVisualDriveFor(b.artworkIdentity()))
        assertNull(hold(b, 0))
    }

    private fun hold(current: MusicTrack, position: Long?, state: LyricLoadState = LyricLoadState.READY) =
        AutoMixLyricHold.heldPlan(current, state, position, lineLeadMs = { 0L })

    private fun presentation(track: MusicTrack, position: Long) = SystemPlaybackPresentation(
        SystemPlaybackKey(track.id, 1), track.title, track.artists, null,
        track.lyrics.map { SystemLyricLine(it.timeMs, it.text) }, position, 1_000L, track.durationMs, advancing = true,
    )

    private class FakeTransition(
        override val plan: AutoMixPlan,
        var clock: Long = 0,
        var to: Long = 0,
        var from: Long = 0,
    ) : AutoMixTransition {
        override fun clockMs() = clock
        override fun toPositionMs() = to
        override fun fromPositionMs() = from
        // 线性的听感曲线便于断言。
        override fun visualProgress(clockMs: Long): Float =
            ((clockMs - plan.enterMs).toFloat() / (plan.fromEndMs - plan.enterMs)).coerceIn(0f, 1f)
    }

    private fun track(id: String, lyrics: List<TimedLyric> = emptyList()) =
        MusicTrack(id, MusicSource.QQ, id, "歌手", "", 240_000, 0, 0, "", "", artworkUrl = "https://example.invalid/$id.jpg",
            lyrics = lyrics)
}
