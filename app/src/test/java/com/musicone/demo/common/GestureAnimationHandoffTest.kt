package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class GestureAnimationHandoffTest {
    @Test fun firstDisplayFrameAdvancesTheOriginalSpringAtBothRefreshRates() = runBlocking {
        for (frameNanos in listOf(16_666_667L, 8_333_333L)) {
            val clock = BroadcastFrameClock()
            withContext(clock) {
                for (velocity in listOf(-1.2f, 0f)) {
                    val value = Animatable(.6f)
                    val spring = PlayerGestureSettlementAnimation
                    val expected = TargetBasedAnimation(spring, Float.VectorConverter, .6f, 0f, velocity)
                    val job = launch {
                        value.animateToWithGestureHandoff(0f, spring, velocity, releaseTimeNanos = 0L,
                            nowNanos = { 0L })
                    }
                    yield()
                    assertEquals(.6f, value.value, .0001f)
                    assertEquals(velocity, value.velocity, .0001f)
                    clock.sendFrame(frameNanos)
                    yield()
                    assertEquals(expected.getValueFromNanos(frameNanos), value.value, .0001f)
                    assertEquals(expected.getVelocityVectorFromNanos(frameNanos).value, value.velocity, .0001f)
                    assertTrue("停住再松手也不应额外等待零进度帧", value.value < .6f)
                    job.cancelAndJoin()
                }
            }
        }
    }

    @Test fun earlierVsyncAdvancesAtActualDeliveryInsteadOfRepeatingTheReleasePosition() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            val releaseTime = 25_000_000L
            val value = Animatable(.4f)
            val spring = PlayerGestureSettlementAnimation
            val expected = TargetBasedAnimation(spring, Float.VectorConverter, .4f, 1f, 1f)
            var deliveredAt = 35_000_000L
            val job = launch { value.animateToWithGestureHandoff(1f, spring, 1f, releaseTime,
                nowNanos = { deliveredAt }) }
            yield()
            clock.sendFrame(16_666_667L)
            yield()
            assertEquals(expected.getValueFromNanos(10_000_000L), value.value, .0001f)
            assertTrue(value.value > .4f)
            deliveredAt = 76_000_000L
            clock.sendFrame(75_000_000L)
            yield()
            assertEquals(expected.getValueFromNanos(51_000_000L), value.value, .0001f)
            job.cancelAndJoin()
        }
    }

    @Test fun handoffRetainsAnimatableBoundsAndCanBeInterruptedByANewDrag() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            val value = Animatable(.95f).apply { updateBounds(0f, 1f) }
            val job = launch {
                value.animateToWithGestureHandoff(1f, PlayerGestureSettlementAnimation, 6f, 0L,
                    nowNanos = { 0L })
            }
            yield()
            clock.sendFrame(16_666_667L)
            yield()
            assertEquals(1f, value.value, 0f)
            assertTrue(job.isCompleted)
            value.snapTo(.6f)
            val next = launch {
                value.animateToWithGestureHandoff(0f, PlayerGestureSettlementAnimation, -1f, 20_000_000L,
                    nowNanos = { 0L })
            }
            yield()
            clock.sendFrame(36_666_667L)
            yield()
            val position = value.value
            next.cancelAndJoin()
            assertEquals(position, value.value, 0f)
            value.snapTo(.7f)
            assertEquals(.7f, value.value, 0f)
        }
    }

    @Test fun eventAgeIsPreservedWhenInputIsHandledAfterItsActualRelease() {
        assertEquals(25_000_000L, gestureReleaseTimeNanos(25L, 30L, 30_000_000L))
        assertEquals(30_000_000L, gestureReleaseTimeNanos(35L, 30L, 30_000_000L))
    }
}
