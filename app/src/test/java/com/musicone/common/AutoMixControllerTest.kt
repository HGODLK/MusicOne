package com.musicone

import android.content.ContextWrapper
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 控制器状态流转：分析时机、排程、交接、完成、撤销与保留分析结果。
 * 用虚拟时间驱动每秒一次的巡检和 33ms 的过渡节拍，输出端用真实 AutoMixAudioSink（内层为记录用假对象）。
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AutoMixControllerTest {
    private val a = autoMixTestTrack("a")
    private val b = autoMixTestTrack("b")
    private val c = autoMixTestTrack("c")
    private val d = autoMixTestTrack("d")

    private lateinit var fake: FakeAutoMixPlayer
    private lateinit var host: Host
    private var enabled = true
    private var clipMs = 0L
    private val analyzed = mutableListOf<Pair<String, String>>()

    private inner class Host : AutoMixHost {
        override var state = MusicOneUiState(currentTrack = a, queue = listOf(a, b, c, d))
        override var generation = 1L
        override val player get() = fake.player
        var next: MusicTrack? = b
        var allowed = true
        val handoffs = mutableListOf<String>()
        override fun naturalNext() = next
        override suspend fun preparedNext(track: MusicTrack) = autoMixTestResolved(track)
        override fun mayTransition() = allowed
        override fun commitHandoff(to: ResolvedPlayback) {
            handoffs += to.track.id
            state = state.copy(currentTrack = to.track)
        }
    }

    @Before fun setUp() {
        resetSession()
        fake = FakeAutoMixPlayer()
        fake.items += MediaItem.Builder().setMediaId(a.id).build()
        host = Host()
        AutoMixEngine.sink = AutoMixAudioSink(RecordingAudioSink(AUTOMIX_TEST_FORMAT).sink)
    }

    @After fun tearDown() {
        AutoMixEngine.sink = null
        resetSession()
    }

    private fun resetSession() {
        AutoMixSession.end(AutoMixEnd.INTERRUPTED)
    }

    private fun TestScope.controller(): AutoMixController {
        val deps = AutoMixControllerDeps(
            now = { testScheduler.currentTime },
            post = { it() },
            enabled = { enabled },
            preparer = { _ ->
                { from, _, to -> analyzed += from.id to to.track.id; autoMixTestPrepared(from, to) }
            },
            bItem = { _, to, _, tag ->
                // 与真实实现一致：B 起初顶着 A 的身份（mediaId 取当前歌曲）
                MediaItem.Builder().setMediaId(host.state.currentTrack!!.id).build().also { fake.tags[it] = tag; check(to.id.isNotEmpty()) }
            },
            tagOf = fake::tagOf,
            markActive = { _, _ -> },
            clipClockMs = { clipMs },
        )
        return AutoMixController(ContextWrapper(null), backgroundScope, host, deps).also { it.start() }
    }

    /** 巡检到排程完成：切歌后等 5 秒才分析，下一轮巡检排程。 */
    private fun TestScope.armFirst(controller: AutoMixController) {
        advanceTimeBy(5_500L); runCurrent()
        assertTrue("切歌后 5 秒内不能开始分析", analyzed.isEmpty())
        advanceTimeBy(2_000L); runCurrent()
        assertEquals(1, analyzed.size)
        assertEquals(2, fake.items.size)
        assertTrue(controller.busy)
    }

    /** 片段开始 → 交接 → 播放器进入 B 条目 → 完成。 */
    private fun TestScope.runTransition(controller: AutoMixController, to: MusicTrack) {
        controller.onClipStarted()
        assertNotNull(AutoMixSession.active.value)
        clipMs = 8_000L
        advanceTimeBy(40L); runCurrent()
        assertEquals(to.id, host.handoffs.last())
        assertTrue("交接点两个条目都改成 B 的身份", fake.ids.all { it == to.id })
        fake.currentIndex = 1
        clipMs = 14_000L
        advanceTimeBy(40L); runCurrent()
        assertNull(AutoMixSession.active.value)
        assertEquals(AutoMixEnd.COMPLETED, AutoMixSession.lastEnd.value?.second)
        assertEquals("完成后只留下 B", listOf(to.id), fake.ids)
        assertEquals(0, fake.currentIndex)
        assertFalse(controller.busy)
        clipMs = 0L
    }

    @Test fun naturalTransitionAnalysesArmsHandsOffAndCompletes() = runTest {
        val controller = controller()
        armFirst(controller)
        runTransition(controller, b)
        controller.stop()
    }

    @Test fun consecutiveTransitionsTrackOnlyTheirOwnNextItem() = runTest {
        val controller = controller()
        armFirst(controller)
        runTransition(controller, b)

        // 当前条目是上一次留下的 B，同样带起播标记；新 B 用新的序号
        host.next = c
        advanceTimeBy(7_000L); runCurrent()
        assertEquals(listOf("a" to "b", "b" to "c"), analyzed)
        assertEquals(2, fake.items.size)

        // 条件变化撤销：只能移除新排进去的 C，不能误删正在播的条目
        host.next = d
        advanceTimeBy(1_000L); runCurrent()
        assertEquals(listOf(b.id), fake.ids)
        assertEquals(0, fake.currentIndex)

        // 重新分析 B → D 后第二次过渡完成，旧条目被清掉
        advanceTimeBy(7_000L); runCurrent()
        assertEquals("b" to "d", analyzed.last())
        runTransition(controller, d)
        controller.stop()
    }

    @Test fun passingTheCutPointKeepsTheAnalysisForASeekBack() = runTest {
        val controller = controller()
        fake.positionMs = 220_000L
        advanceTimeBy(8_000L); runCurrent()
        assertEquals(1, analyzed.size)
        assertEquals("越过切入点不排程", 1, fake.items.size)

        fake.positionMs = 100_000L
        advanceTimeBy(1_000L); runCurrent()
        assertEquals(2, fake.items.size)
        assertEquals("拖回后直接复用分析结果", 1, analyzed.size)
        controller.stop()
    }

    @Test fun sinkGivingUpOnAMissedCutPointKeepsTheResultButOnlyReArmsBeforeTheClip() = runTest {
        val controller = controller()
        armFirst(controller)
        fake.positionMs = 199_000L
        controller.onAborted(AutoMixAudioSink.MISSED_REASON)
        assertEquals("撤掉排进去的 B", listOf(a.id), fake.ids)

        // 片段起点前 1.5 秒以内不再排程，避免在临界点反复排程又取消
        advanceTimeBy(1_000L); runCurrent()
        assertEquals(1, fake.items.size)

        fake.positionMs = 150_000L
        advanceTimeBy(1_000L); runCurrent()
        assertEquals(2, fake.items.size)
        assertEquals(1, analyzed.size)
        controller.stop()
    }

    @Test fun seekingBeforeHandoffStaysOnAAndKeepsTheAnalysis() = runTest {
        val controller = controller()
        armFirst(controller)
        controller.onClipStarted()
        assertTrue(controller.seek(fake.player, 60_000L))
        assertEquals(listOf(a.id), fake.ids)
        assertEquals(AutoMixEnd.INTERRUPTED, AutoMixSession.lastEnd.value?.second)
        assertTrue(host.handoffs.isEmpty())

        fake.positionMs = 60_000L
        advanceTimeBy(6_000L); runCurrent()
        assertEquals(2, fake.items.size)
        assertEquals(1, analyzed.size)
        controller.stop()
    }

    @Test fun seekingAfterHandoffStaysOnB() = runTest {
        val controller = controller()
        armFirst(controller)
        controller.onClipStarted()
        clipMs = 8_000L
        advanceTimeBy(40L); runCurrent()
        assertTrue(controller.seek(fake.player, 30_000L))
        assertEquals(1, fake.items.size)
        assertEquals(1 to 30_000L, fake.seeks.last())
        assertEquals(AutoMixEnd.INTERRUPTED, AutoMixSession.lastEnd.value?.second)
        controller.stop()
    }

    @Test fun turningTheSwitchOffDisarms() = runTest {
        val controller = controller()
        armFirst(controller)
        enabled = false
        advanceTimeBy(1_000L); runCurrent()
        assertEquals(listOf(a.id), fake.ids)
        assertFalse(controller.busy)
        controller.stop()
    }

    @Test fun noAnalysisWhileAPreviousTransitionIsStillOnScreen() = runTest {
        val controller = controller()
        AutoMixSession.begin(AutoMixTransitionClock(autoMixTestPrepared(a, autoMixTestResolved(b))) { 0L })
        advanceTimeBy(20_000L); runCurrent()
        assertTrue(analyzed.isEmpty())
        AutoMixSession.end(AutoMixEnd.COMPLETED)
        advanceTimeBy(3_000L); runCurrent()
        assertTrue("过渡结束后重新计 5 秒稳定时间", analyzed.isEmpty())
        advanceTimeBy(3_000L); runCurrent()
        assertEquals(1, analyzed.size)
        controller.stop()
    }

    @Test fun nothingIsAnalysedWhenTheSwitchIsOffOrThereIsNoNaturalNext() = runTest {
        val controller = controller()
        enabled = false
        advanceTimeBy(10_000L); runCurrent()
        enabled = true
        host.next = null
        advanceTimeBy(10_000L); runCurrent()
        assertTrue(analyzed.isEmpty())
        controller.stop()
    }
}
