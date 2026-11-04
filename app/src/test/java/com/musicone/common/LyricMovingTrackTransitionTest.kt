package com.musicone

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LyricMovingTrackTransitionTest {
    private fun window(id: String) = LyricWindow(LyricPresentation(id = id, source = MusicSource.QQ,
        durationMs = 10000L, lyrics = listOf(TimedLyric(0L, id))))

    @Test fun outgoingExpandedBoundaryFreezesItsTurnProgressButKeepsTranslationCompensation() {
        val layout = LyricFrozenWindowLayout()
        var progress = .5f
        val boundary = LyricBoundaryWindow(40f, offset = { 10f }, partialTop = true,
            edges = { height, shift -> LyricBoundaryEdges((340f - shift) * progress,
                height - (400f + shift) * progress) })
        val geometry = LyricWindowGeometry(lyricTypographyForWidth(PlayerLayoutMode.COMPACT_SINGLE, 340f),
            LyricReadingBounds(340f, 480f), boundary, 0.dp, 40, 40)
        layout.record(geometry)
        layout.constraints(Constraints.fixed(340, 1500), false)
        val frozen = layout.geometry(geometry, true).boundaryWindow!!
        progress = 1f
        assertTrue(frozen.partialTop)
        assertEquals(LyricBoundaryEdges(165f, 1295f), frozen.edges!!.invoke(1500f, 10f))
        assertEquals(LyricBoundaryEdges(145f, 1275f), frozen.edges!!.invoke(1500f, 30f))
    }

    @Test fun movementSelectsFadeAndTheCurrentTransactionDoesNotSwitchToSlideAtRest() {
        assertFalse(lyricTrackUsesFade(false, false, false))
        assertTrue(lyricTrackUsesFade(true, false, false))
        assertTrue(lyricTrackUsesFade(false, true, false))
        assertTrue(lyricTrackUsesFade(false, false, true))
        assertFalse(lyricTrackUsesFade(true, false, false, displayed = false))
        assertTrue(lyricTrackUsesFade(true, false, false, displayed = true))
        assertTrue(lyricTrackUsesFade(false, false, true, displayed = false))
        // 交接完成后的下一次切歌恢复原滑动，不补播已完成曲目的动画。
        assertFalse(lyricTrackUsesFade(false, false, false))
    }

    @Test fun outgoingGeometryKeepsItsLastTypographyAndConstraintsDuringRotation() {
        val layout = LyricFrozenWindowLayout()
        var offset = 10f
        val portrait = LyricWindowGeometry(lyricTypographyForWidth(PlayerLayoutMode.COMPACT_SINGLE, 340f),
            LyricReadingBounds(40f, 80f), LyricBoundaryWindow(40f, { offset }), 0.dp, 40, 40)
        val landscape = portrait.copy(typography = lyricTypographyForWidth(PlayerLayoutMode.COMPACT_SINGLE, 480f),
            readingBounds = LyricReadingBounds(20f, 20f), touchPadding = 64.dp, topExtension = 0, topBuffer = 0)
        layout.record(portrait)
        val oldConstraints = Constraints.fixed(340, 800)
        val newConstraints = Constraints.fixed(500, 350)
        assertEquals(oldConstraints, layout.constraints(oldConstraints, false))
        offset = 25f
        val frozen = layout.geometry(landscape, true)
        assertEquals(portrait.typography, frozen.typography)
        assertEquals(portrait.readingBounds, frozen.readingBounds)
        assertEquals(0.dp, frozen.touchPadding)
        assertEquals(oldConstraints, layout.constraints(newConstraints, true))
        offset = 180f
        assertSame(frozen, layout.geometry(landscape, true))
        assertEquals(25f, frozen.boundaryWindow!!.offset(), 0f)
        assertEquals(landscape, layout.geometry(landscape, false))
        assertEquals(newConstraints, layout.constraints(newConstraints, false))
    }

    @Test fun ordinaryFadeWaitsForRealLayoutAndRetargetsItsVisibleBlendWithoutGesturePreparation() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            var time = 0L
            suspend fun frames(count: Int) {
                repeat(count) {
                    yield(); time += 16_666_667L; clock.sendFrame(time)
                    Snapshot.sendApplyNotifications(); yield()
                }
            }
            try {
                val blend = LyricHeldTrackTransition()
                val a = window("a")
                val b = window("b")
                blend.request(a, b, this, gesturePreparation = false)
                assertNull(blend.preparation)
                assertTrue(blend.awaitingLayout)
                frames(8)
                assertEquals(1f, blend.weight(a), 0f)
                assertEquals(0f, blend.weight(b), 0f)
                b.ready.complete(Unit)
                frames(10)
                assertFalse(blend.awaitingLayout)
                val before = blend.layers.map { LyricHeldBlend(it.window, it.weight) }
                assertTrue(blend.weight(b) > 0f && blend.weight(b) < 1f)
                val c = window("c")
                blend.request(b, c, this, gesturePreparation = false)
                before.forEach { assertEquals(it.weight, blend.weight(it.window), 0f) }
                frames(4)
                before.forEach { assertEquals(it.weight, blend.weight(it.window), 0f) }
                c.ready.complete(Unit)
                frames(80)
                assertFalse(blend.active)
                assertEquals(listOf(c), blend.layers.map { it.window })
                assertEquals(1f, blend.weight(c), 0f)
            } finally { coroutineContext.cancelChildren() }
        }
    }

    @Test fun replacingAnUnseenTargetCannotReleaseTheLatestFadeWithItsOldReadiness() = runBlocking {
        val blend = LyricHeldTrackTransition()
        val a = window("a")
        val b = window("b")
        val c = window("c")
        blend.request(a, b, this, gesturePreparation = false)
        yield()
        blend.request(b, c, this, gesturePreparation = false)
        b.ready.complete(Unit)
        yield()
        assertTrue(blend.awaitingLayout)
        assertEquals(listOf(a, c), blend.layers.map { it.window })
        assertEquals(1f, blend.weight(a), 0f)
        assertEquals(0f, blend.weight(c), 0f)
        blend.cancel()
    }

    @Test fun movingSlideSourcesKeepTheirActualOpacityInsteadOfBeingRenormalizedDarker() = runBlocking {
        val blend = LyricHeldTrackTransition()
        val a = window("a")
        val b = window("b")
        val c = window("c")
        blend.request(b, c, this, gesturePreparation = false,
            initialSources = listOf(LyricHeldBlend(a, 1f), LyricHeldBlend(b, 1f)))
        assertEquals(1f, blend.weight(a), 0f)
        assertEquals(1f, blend.weight(b), 0f)
        assertEquals(0f, blend.weight(c), 0f)
        blend.cancel()
    }

    @Test fun anUnseenFirstTargetNeverBecomesAnOpaqueSourceWhenReplaced() = runBlocking {
        val blend = LyricHeldTrackTransition()
        val unseen = window("b")
        val latest = window("c")
        blend.request(null, unseen, this, gesturePreparation = false)
        blend.request(unseen, latest, this, gesturePreparation = false)
        assertEquals(listOf(latest), blend.layers.map { it.window })
        assertEquals(0f, blend.weight(unseen), 0f)
        assertEquals(0f, blend.weight(latest), 0f)
        blend.cancel()
    }

    @Test fun freezingAnExistingSeekKeepsBothDisplayedPanesAndTheActualOffset() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            var time = 0L
            suspend fun frames(count: Int) {
                repeat(count) {
                    yield(); time += 16_666_667L; clock.sendFrame(time)
                    Snapshot.sendApplyNotifications(); yield()
                }
            }
            try {
                val controller = LyricLayerTransitionController("a", 0)
                controller.beginTransition(10, 1f, 600f, this)
                controller.currentPane.ready.complete(Unit)
                frames(8)
                val current = controller.currentPane
                val outgoing = controller.outgoingPane
                val offset = controller.seekOffset.value
                assertTrue(offset > 0f && offset < 600f)
                controller.freezeForTrackFade()
                frames(30)
                controller.finishPreview()
                controller.cancel()
                assertSame(current, controller.currentPane)
                assertSame(outgoing, controller.outgoingPane)
                assertTrue(current.isFrozen)
                assertEquals(offset, controller.seekOffset.value, 0f)
                assertEquals(LyricLayerTransitionState.TRANSITIONING, controller.state)
            } finally { coroutineContext.cancelChildren() }
        }
    }

    @Test fun retiringAWindowStopsItsRunningRowDriverBeforeAnotherFrameCanAdvanceIt() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            var time = 0L
            suspend fun frames(count: Int) {
                repeat(count) {
                    yield(); time += 16_666_667L; clock.sendFrame(time)
                    Snapshot.sendApplyNotifications(); yield()
                }
            }
            try {
                val step = LyricPlaybackStepMotion(0, nowNanos = { time })
                val row = step.row(1)
                step.emphasize(1, 1, animate = true, playback = true)
                val driver = launch { step.run() }
                frames(8)
                val before = row.played.value
                assertTrue(before > 0f && before < 1f)
                step.freezeForTrackFade()
                frames(1)
                assertTrue(driver.isCompleted)
                assertEquals(before, row.played.value, 0f)
                val restarted = launch { step.run() }
                frames(20)
                assertTrue(restarted.isCompleted)
                assertEquals(before, row.played.value, 0f)
            } finally { coroutineContext.cancelChildren() }
        }
    }
}
