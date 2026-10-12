package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LyricHeldTrackTransitionTest {
    private fun window(id: String) = LyricWindow(LyricPresentation(
        source = MusicSource.QQ, id = id, durationMs = 10000L,
        lyrics = listOf(TimedLyric(0L, id)),
    ))

    @Test fun unpreparedTargetKeepsSourceAndStaleReadinessCannotReleaseLatestRequest() = runBlocking {
        val controller = LyricHeldTrackTransition()
        val source = window("a")
        val obsolete = window("b")
        controller.request(source, obsolete, this)
        val oldReady = controller.preparation!!
        yield()
        assertEquals(1f, controller.weight(source), 0f)
        assertEquals(0f, controller.weight(obsolete), 0f)
        val latest = window("c")
        controller.request(obsolete, latest, this)
        oldReady.ready.complete(Unit)
        yield()
        assertFalse(controller.preparation!!.ready.isCompleted)
        assertEquals(1f, controller.weight(source), 0f)
        assertFalse(controller.layers.any { it.window === obsolete })
        controller.cancel()
    }

    @Test fun interruptedFadeContinuesItsActualBlendAndCompletesWithoutGestureProgress() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            var time = 0L
            suspend fun frames(count: Int) {
                repeat(count) {
                    yield()
                    time += 16_666_667L
                    clock.sendFrame(time)
                    Snapshot.sendApplyNotifications()
                    yield()
                }
            }
            val controller = LyricHeldTrackTransition()
            val a = window("a")
            val b = window("b")
            controller.request(a, b, this)
            controller.preparation!!.ready.complete(Unit)
            frames(10)
            val before = controller.layers.map { LyricHeldBlend(it.window, it.weight) }
            assertTrue(controller.weight(b) in .001f.. .999f)
            val c = window("c")
            controller.request(b, c, this)
            before.forEach { assertEquals(it.weight, controller.weight(it.window), .0001f) }
            controller.preparation!!.ready.complete(Unit)
            frames(60)
            assertFalse(controller.active)
            assertEquals(listOf(c), controller.layers.map { it.window })
            assertEquals(1f, controller.weight(c), 0f)
            // 退出中间态后正常切到别的歌，再按住切歌不能接回上次渐变的来源。
            val d = window("d")
            val e = window("e")
            controller.request(d, e, this)
            assertEquals(1f, controller.weight(d), 0f)
            assertEquals(0f, controller.weight(c), 0f)
            controller.cancel()
        }
    }

    @Test fun transparentBlendWeightsRemainComplementaryAcrossRetargeting() {
        val a = window("a")
        val b = window("b")
        val c = window("c")
        val starts = listOf(LyricHeldBlend(a, .35f), LyricHeldBlend(b, .65f))
        for (step in 0..100) {
            val layers = lyricHeldBlendWeights(starts, c, step / 100f)
            assertEquals(1f, layers.sumOf { it.weight.toDouble() }.toFloat(), .0001f)
        }
    }

    @Test fun releasedFadeKeepsItsVisibleWeightsForTheNormalOutgoingPages() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            val controller = LyricHeldTrackTransition()
            val a = window("a")
            val b = window("b")
            controller.request(a, b, this)
            controller.preparation!!.ready.complete(Unit)
            repeat(10) { index ->
                yield()
                clock.sendFrame((index + 1) * 16_666_667L)
                Snapshot.sendApplyNotifications()
                yield()
            }
            val weights = controller.layers.map { LyricHeldBlend(it.window, it.weight) }
            assertTrue(controller.weight(b) in .001f.. .999f)
            assertEquals(listOf(a, b), controller.releaseForSlide())
            assertFalse(controller.active)
            assertTrue(controller.hasSlidingBlend)
            weights.forEach { assertEquals(it.weight, controller.slidingWeight(it.window)!!, 0f) }
            repeat(60) { index ->
                yield()
                clock.sendFrame((index + 11) * 16_666_667L)
                Snapshot.sendApplyNotifications()
                yield()
            }
            weights.forEach { assertEquals(it.weight, controller.slidingWeight(it.window)!!, 0f) }
            controller.forgetSlide(a)
            assertNull(controller.slidingWeight(a))
            assertNotNull(controller.slidingWeight(b))
            controller.clearSlides()
            assertFalse(controller.hasSlidingBlend)
        }
    }

    @Test fun releaseDuringPreparationDoesNotPromoteTheInvisibleTarget() = runBlocking {
        val controller = LyricHeldTrackTransition()
        val a = window("a")
        val b = window("b")
        controller.request(a, b, this)
        val preparation = controller.preparation!!
        yield()
        assertEquals(listOf(a), controller.releaseForSlide())
        preparation.ready.complete(Unit)
        yield()
        assertFalse(controller.active)
        assertEquals(1f, controller.slidingWeight(a)!!, 0f)
        assertNull(controller.slidingWeight(b))
        controller.clearSlides()
    }

    @Test fun readinessRequiresThePreparedIntermediatePositionAndCurrentGeometry() {
        assertTrue(lyricHeldWindowAligned(true, -110, -110.4f, .5f, .5f, 0f, false))
        assertFalse(lyricHeldWindowAligned(true, 0, -110f, .5f, .5f, 0f, false))
        assertFalse(lyricHeldWindowAligned(true, -110, -110f, .6f, .5f, 0f, false))
        assertFalse(lyricHeldWindowAligned(false, -110, -110f, .5f, .5f, 0f, false))
        assertFalse(lyricHeldWindowAligned(true, null, -110f, .5f, .5f, 0f, false))
        assertFalse(lyricHeldWindowAligned(true, -110, -110f, .5f, .5f, 20f, false))
        assertFalse(lyricHeldWindowAligned(true, -110, -110f, .5f, .5f, 0f, true))
    }
}
