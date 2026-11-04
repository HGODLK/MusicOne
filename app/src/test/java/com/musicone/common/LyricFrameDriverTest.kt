package com.musicone

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricFrameDriverTest {
    @Test fun closingFramesReachTheHostWithoutPlaybackAndStopWithTheActualMotion() = runBlocking {
        for (landscape in listOf(false, true)) {
            val upstream = BroadcastFrameClock()
            withContext(upstream) {
                val driver = LyricFrameDriver()
                val draws = mutableListOf<Long>()
                driver.register(Any()) { draws += it }
                val motion = PhoneLyricsMotion(true, frameDriver = driver)
                val closing = launch {
                    if (landscape) motion.moveLandscape(false, 1800f) else motion.moveTo(false)
                }
                var time = 0L
                // 等关闭协程真正开始等帧后再发第一帧，否则这一帧没有接收方。
                repeat(8) { yield() }
                val gaps = listOf(8L, 16L, 95L, 11L, 33L, 7L)
                // 没有播放时钟参与；页眉和歌词同时请求同一帧也只能提交一次。
                repeat(120) { index ->
                    yield()
                    time += gaps[index % gaps.size] * 1_000_000L
                    upstream.sendFrame(time)
                    Snapshot.sendApplyNotifications()
                    yield()
                    if (index < gaps.size) assertEquals(time, draws.last())
                }
                assertEquals(draws.size, draws.distinct().size)
                assertEquals(true, closing.isCompleted)
                assertEquals(0f, motion.lyricsProgress, 0f)
                val count = draws.size
                repeat(6) {
                    time += 16_000_000L
                    upstream.sendFrame(time)
                    yield()
                }
                assertEquals("开合结束后没有额外刷新循环", count, draws.size)
            }
        }
    }

    @Test fun existingLyricFramesDriveWindowOnceAndKeepTheIndependentCap() = runBlocking {
        val upstream = BroadcastFrameClock()
        val driver = LyricFrameDriver()
        val owner = Any()
        val draws = mutableListOf<Long>()
        driver.register(owner) { draws += it }
        val clock = AnimationFrameClock(upstream, { 60 }, onFrameAccepted = driver::onFrame)
        // 模拟播放时钟和行级动画同时消费同一个歌词帧。
        val jobs = List(2) { launch(start = CoroutineStart.UNDISPATCHED) {
            while (isActive) clock.withFrameNanos { }
        } }
        repeat(120) { frame ->
            upstream.sendFrame(frame * 1_000_000_000L / 120)
            yield()
        }
        assertEquals(60, draws.size)
        assertEquals(draws.size, draws.distinct().size)
        driver.remove(owner)
        repeat(30) { frame ->
            upstream.sendFrame((frame + 120) * 1_000_000_000L / 120)
            yield()
        }
        assertEquals(60, draws.size)
        jobs.forEach { it.cancelAndJoin() }
    }
}
