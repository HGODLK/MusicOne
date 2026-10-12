package com.musicone.demo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.ViewConfiguration
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.*
import org.junit.Test

class OwnedVerticalGestureTest {
    @Test fun liftHookDoesNotMoveThePictureBackBeforeForwardSettlement() {
        val down = change(0L, 0f, 0L, 0f, true)
        val release = GestureReleaseVelocity(0L, stablePlayerSettlement = true)
        val moves = mutableListOf<Float>()
        val displacement = PlayerGestureDisplacement(false)
        val end = events(change(20L, 20f, 0L, 0f, true),
            change(30L, 18f, 20L, 20f, false)).runGesture {
            awaitOwnedVerticalGesture(down, tracker(down), release,
                begin = { it > 0f }, move = moves::add, observe = displacement::move)
        }
        assertEquals(VerticalGestureEnd(true, 30L), end)
        assertEquals(12f, moves.sum(), .001f)
        assertEquals(20f, displacement.distancePx, .001f)
        assertEquals(1000f, release.settlingVelocity(30L, 0f, true), .001f)
    }

    @Test fun sidewaysDriftAfterTakeoverDoesNotCancelVerticalMomentum() {
        val down = change(0L, 0f, 0L, 0f, true)
        val sideways = change(40L, 30f, 20L, 20f, true)
            .copy(currentPosition = Offset(40f, 30f))
        val moves = mutableListOf<Float>()
        val end = events(change(20L, 20f, 0L, 0f, true), sideways,
            change(50L, 35f, 40L, 30f, false)).runGesture {
            awaitOwnedVerticalGesture(down, tracker(down), GestureReleaseVelocity(0L, stablePlayerSettlement = true),
                begin = { it > 0f }, move = moves::add)
        }
        assertEquals(VerticalGestureEnd(true, 50L), end)
        assertEquals(27f, moves.sum(), .001f)
    }

    @Test fun intentObservesTheFullPathWhileVisualDragSubtractsTouchSlop() {
        val down = change(0L, 0f, 0L, 0f, true)
        val drag = PlayerGestureDisplacement(false)
        val moves = mutableListOf<Float>()
        val scope = events(change(20L, 7f, 0L, 0f, true),
            change(40L, 20f, 20L, 7f, true), change(50L, 11f, 40L, 20f, false))
        val end = scope.runGesture {
            awaitOwnedVerticalGesture(down, tracker(down), GestureReleaseVelocity(0L),
                begin = { it > 0f }, move = moves::add, observe = drag::move)
        }
        assertEquals(VerticalGestureEnd(true, 50L), end)
        assertEquals(11f, drag.distancePx, 0f)
        assertEquals(9f, drag.reverseDistancePx, 0f)
        assertEquals(3f, moves.sum(), 0f)
    }

    @Test fun shortSwipeCrossingSlopOnlyOnUpStillCommitsItsFullTrajectory() {
        val moves = mutableListOf<Float>()
        val release = GestureReleaseVelocity(0L)
        val down = change(0L, 0f, 0L, 0f, true)
        val scope = events(change(20L, 7f, 0L, 0f, true), change(40L, 24f, 20L, 7f, false))
        val result = scope.runGesture {
            awaitOwnedVerticalGesture(down, tracker(down), release,
                begin = { it > 0f }, move = moves::add)
        }
        assertEquals(VerticalGestureEnd(true, 40L), result)
        assertEquals(16f, moves.sum(), .001f)
        assertEquals(24f, release.effectiveDistancePx, .001f)
        assertTrue(shouldDismissPlayer(.98f, release.released(40L, 0f, true), true,
            release.effectiveDistancePx))
    }

    @Test fun childConsumptionAfterTakeoverDoesNotCancelTheFinalUp() {
        val down = change(0L, 0f, 0L, 0f, true)
        val up = change(40L, 40f, 20L, 20f, false).apply { consume() }
        val moves = mutableListOf<Float>()
        val result = events(change(20L, 20f, 0L, 0f, true), up).runGesture {
            awaitOwnedVerticalGesture(down, tracker(down), GestureReleaseVelocity(0L),
                begin = { it > 0f }, move = moves::add)
        }
        assertEquals(VerticalGestureEnd(true, 40L), result)
        assertEquals(32f, moves.sum(), .001f)
    }

    @Test fun smallTapAndHorizontalSwipeLeaveTheChildInteractionAvailable() {
        val down = change(0L, 0f, 0L, 0f, true)
        val up = change(20L, 4f, 0L, 0f, false)
        assertNull(events(up).runGesture {
            awaitOwnedVerticalGesture(down, tracker(down), GestureReleaseVelocity(0L),
                begin = { fail("点击不能启动拖动"); false }, move = {})
        })
        assertFalse(up.isConsumed)
        val horizontal = change(20L, 2f, 0L, 0f, true).copy(currentPosition = Offset(40f, 2f))
        assertNull(events(horizontal).runGesture {
            awaitOwnedVerticalGesture(down, tracker(down), GestureReleaseVelocity(0L),
                begin = { fail("横划不能启动纵向拖动"); false }, move = {})
        })
        assertFalse(horizontal.isConsumed)
    }

    @Test fun missingPointerIsARealCancellationAfterTakeover() {
        val down = change(0L, 0f, 0L, 0f, true)
        val other = change(40L, 40f, 20L, 20f, true).copy(id = PointerId(2L))
        val result = events(change(20L, 20f, 0L, 0f, true), other).runGesture {
            awaitOwnedVerticalGesture(down, tracker(down), GestureReleaseVelocity(0L),
                begin = { it > 0f }, move = {})
        }
        assertEquals(VerticalGestureEnd(false, 20L), result)
    }

    @Test fun unsupportedDirectionRelinquishesTheGestureBeforeAChildTakesIt() {
        val down = change(0L, 0f, 0L, 0f, true)
        val upward = change(20L, -20f, 0L, 0f, true)
        assertNull(events(upward).runGesture {
            awaitOwnedVerticalGesture(down, tracker(down), GestureReleaseVelocity(0L),
                begin = { it > 0f }, move = { fail("返回手势不能接管上划") })
        })
        assertFalse(upward.isConsumed)
    }

    private fun tracker(down: PointerInputChange) = VelocityTracker().apply {
        addPosition(down.uptimeMillis, down.position)
    }

    // 受限挂起函数必须从同一事件接收者调用；同步队列用空上下文启动，无需 runBlocking。
    private fun <T> AwaitPointerEventScope.runGesture(block: suspend AwaitPointerEventScope.() -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(this, object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { outcome = result }
        })
        return checkNotNull(outcome) { "同步事件队列不应留下未完成的挂起" }.getOrThrow()
    }

    private fun change(time: Long, y: Float, previousTime: Long, previousY: Float, pressed: Boolean) =
        PointerInputChange(PointerId(1L), time, Offset(0f, y), pressed,
            previousTime, Offset(0f, previousY), true, false)

    // 用同步事件队列运行真实识别函数，不依赖 Android UI 或改变测试依赖。
    private fun events(vararg changes: PointerInputChange): AwaitPointerEventScope {
        val configuration = Proxy.newProxyInstance(ViewConfiguration::class.java.classLoader,
            arrayOf(ViewConfiguration::class.java)) { _, method, _ ->
            when (method.name) {
                "getTouchSlop" -> 8f
                else -> error("不应读取的配置：${method.name}")
            }
        } as ViewConfiguration
        val queue = changes.map { PointerEvent(listOf(it)) }.iterator()
        return Proxy.newProxyInstance(AwaitPointerEventScope::class.java.classLoader,
            arrayOf(AwaitPointerEventScope::class.java)) { _, method, args ->
            when (method.name) {
                "getViewConfiguration" -> configuration
                "awaitPointerEvent" -> {
                    assertEquals(PointerEventPass.Initial, args!![0])
                    queue.next()
                }
                else -> error("不应调用的事件方法：${method.name}")
            }
        } as AwaitPointerEventScope
    }
}
