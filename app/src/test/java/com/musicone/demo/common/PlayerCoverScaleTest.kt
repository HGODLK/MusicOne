package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PlayerCoverScaleTest {
    @Test fun retainedPausedCoverCapturesPlayingSizeBeforeOpeningOnBothLayouts() = runBlocking {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(coroutineContext + clock)
        var time = 0L
        suspend fun frames(count: Int) {
            repeat(count) {
                yield()
                time += 16_666_667L
                clock.sendFrame(time)
                yield()
            }
        }
        for (width in listOf(375f, 1280f)) {
            val scale = PlayerCoverScaleState(false)
            val motion = PageMotion(scope, false, 320, 280, PlayerReturnAnimation, PlayerEnterAnimation)
            motion.updateHost(Rect(0f, 0f, width, 800f))
            assertEquals(.78f, scale.displayedScale(false, MotionPhase.HIDDEN, false), .0001f)
            motion.request(true)
            // 模拟准备首帧先测量、缩放协程尚未执行，锚点也必须使用播放尺寸。
            val preparedScale = scale.displayedScale(true, motion.phase, true)
            assertEquals(1f, preparedScale, .0001f)
            motion.targets["cover"] = MotionAnchor(Rect(40f, 40f,
                40f + 200f * preparedScale, 40f + 200f * preparedScale))
            scale.update(true, motion.phase, true)
            frames(6)
            assertEquals(MotionPhase.MOVING, motion.phase)
            assertEquals(200f, motion.targetSnapshot.getValue("cover").bounds.width, .0001f)
            scale.update(true, motion.phase, true)
            frames(35)
            assertEquals(MotionPhase.SHOWN, motion.phase)
            assertEquals(preparedScale, scale.displayedScale(true, motion.phase, true), .0001f)
        }
        coroutineContext.cancelChildren()
    }

    @Test fun visiblePlayAndPauseKeepTheirSmoothScaleTransition() = runBlocking {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(coroutineContext + clock)
        val scale = PlayerCoverScaleState(true)
        var time = 0L
        suspend fun frames(count: Int) {
            repeat(count) {
                yield()
                time += 16_666_667L
                clock.sendFrame(time)
                yield()
            }
        }
        for (playing in listOf(false, true)) {
            val animation = scope.launch { scale.update(playing, MotionPhase.SHOWN, true) }
            frames(8)
            val middle = scale.displayedScale(playing, MotionPhase.SHOWN, true)
            assertTrue(middle > .78f && middle < 1f)
            frames(25)
            animation.join()
            assertEquals(if (playing) 1f else .78f,
                scale.displayedScale(playing, MotionPhase.SHOWN, true), .0001f)
        }
        coroutineContext.cancelChildren()
    }

    @Test fun leavingDuringScaleAnimationFreezesTheCapturedSizeUntilRebound() = runBlocking {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(coroutineContext + clock)
        val scale = PlayerCoverScaleState(true)
        var time = 0L
        suspend fun frames(count: Int) {
            repeat(count) {
                yield()
                time += 16_666_667L
                clock.sendFrame(time)
                yield()
            }
        }
        val animation = scope.launch { scale.update(false, MotionPhase.SHOWN, true) }
        frames(8)
        val captured = scale.displayedScale(false, MotionPhase.SHOWN, true)
        animation.cancelAndJoin()
        scale.update(false, MotionPhase.DRAGGING, true)
        assertEquals(captured, scale.displayedScale(false, MotionPhase.DRAGGING, true), .0001f)
        scale.update(false, MotionPhase.MOVING, true)
        frames(35)
        assertEquals(captured, scale.displayedScale(false, MotionPhase.MOVING, true), .0001f)
        val rebound = scope.launch { scale.update(false, MotionPhase.SHOWN, true) }
        frames(35)
        rebound.join()
        assertEquals(.78f, scale.displayedScale(false, MotionPhase.SHOWN, true), .0001f)
        coroutineContext.cancelChildren()
    }
}
