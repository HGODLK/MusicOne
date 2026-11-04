package com.musicone

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

class LyricBlurEdgeEaseTest {
    private val threshold = 20f

    @Test fun 连续变化一比一跟随() {
        val ease = LyricBlurEdgeEase()
        for (target in listOf(100f, 110f, 125f, 120f)) {
            assertEquals(target, ease.span(target, threshold), 0f)
        }
    }

    @Test fun 绘制每次都订阅动画值() {
        val ease = LyricBlurEdgeEase()
        ease.span(100f, threshold)
        var reads = 0
        // 跳变这一帧也要订阅，否则动画启动后绘制不会再被触发。
        Snapshot.observe(readObserver = { reads++ }) { ease.span(300f, threshold) }
        assertTrue(reads > 0)
    }

    @Test fun 跳变后从原位平滑移到目标() = runBlocking {
        val clock = BroadcastFrameClock()
        val ease = LyricBlurEdgeEase()
        CoroutineScope(coroutineContext + clock).launch { ease.run() }
        yield()
        assertEquals(100f, ease.span(100f, threshold), 0f)
        // 跳变这一帧不能动。
        assertEquals(100f, ease.span(218f, threshold), 0f)
        var frame = 0L
        var previous = 100f
        repeat(60) {
            yield(); clock.sendFrame(frame * 16_000_000L); frame++; yield()
            val shown = ease.span(218f, threshold)
            assertTrue(shown >= previous - .01f)
            assertTrue(shown - previous < 60f)
            previous = shown
        }
        assertEquals(218f, previous, .01f)
        coroutineContext.cancelChildren()
    }

    @Test fun 动画途中再次跳变从当前显示位置继续() = runBlocking {
        val clock = BroadcastFrameClock()
        val ease = LyricBlurEdgeEase()
        CoroutineScope(coroutineContext + clock).launch { ease.run() }
        yield()
        ease.span(100f, threshold)
        ease.span(218f, threshold)
        var frame = 0L
        repeat(6) { yield(); clock.sendFrame(frame * 16_000_000L); frame++; yield() }
        val shown = ease.span(218f, threshold)
        assertTrue(shown > 100f && shown < 218f)
        assertEquals(shown, ease.span(0f, threshold), .01f)
        var previous = shown
        repeat(60) {
            yield(); clock.sendFrame(frame * 16_000_000L); frame++; yield()
            val next = ease.span(0f, threshold)
            assertTrue(abs(next - previous) < 60f)
            previous = next
        }
        assertEquals(0f, previous, .01f)
        coroutineContext.cancelChildren()
    }
}
