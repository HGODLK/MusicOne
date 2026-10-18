package com.musicone.demo

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class UsbSilenceFeederTest {
    private open class Track : UsbSilenceTrack {
        val playing = AtomicInteger()
        val calls = CopyOnWriteArrayList<Pair<Int, Int>>()
        val entered = CountDownLatch(1)
        val paused = CountDownLatch(1)
        val released = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        override fun play() { playing.incrementAndGet() }
        override fun write(samples: ShortArray, offset: Int, count: Int): Int {
            check(released.count != 0L) { "不能向已释放音轨写入" }
            check(samples.all { it == 0.toShort() }) { "占位数据必须全部为静音" }
            calls += offset to count
            entered.countDown()
            return consume(samples, offset, count)
        }
        open fun consume(samples: ShortArray, offset: Int, count: Int): Int { unblock.await(); return 0 }
        override fun pause() { paused.countDown(); unblock.countDown() }
        override fun release() { released.countDown() }
    }
    private fun await(latch: CountDownLatch) { assertTrue("等待写线程超时", latch.await(4, TimeUnit.SECONDS)) }

    @Test fun activeTrackContinuouslyReceivesZeroPcmInsteadOfOnlyCallingPlay() {
        val fed = CountDownLatch(20)
        val track = object : Track() {
            override fun consume(samples: ShortArray, offset: Int, count: Int): Int {
                fed.countDown()
                if (fed.count == 0L) unblock.await()
                return count
            }
        }
        val opened = AtomicInteger()
        val failures = CopyOnWriteArrayList<Exception>()
        val feeder = UsbSilenceFeeder({ opened.incrementAndGet(); track }, failures::add)
        try {
            feeder.setActive(false)
            assertEquals(0, opened.get())
            feeder.setActive(true)
            await(fed)
            repeat(5) { feeder.setActive(true) }
            assertEquals(1, opened.get())
            assertEquals(1, track.playing.get())
            assertTrue(track.calls.size >= 20)
            assertTrue(track.calls.all { it == (0 to UsbSilenceFeeder.SAMPLES_PER_CHUNK) })
            feeder.setActive(false)
            await(track.paused); await(track.released)
            assertTrue(failures.isEmpty())
        } finally { feeder.release() }
    }

    @Test fun partialAndZeroWritesContinueAtTheUnwrittenOffsetAndThenLoop() {
        val fifth = CountDownLatch(1)
        val track = object : Track() {
            override fun consume(samples: ShortArray, offset: Int, count: Int): Int = when (calls.size) {
                1 -> 0
                2 -> 1_000
                3 -> 2_000
                4 -> 1_800
                else -> { fifth.countDown(); unblock.await(); 0 }
            }
        }
        val failures = CopyOnWriteArrayList<Exception>()
        val feeder = UsbSilenceFeeder({ track }, failures::add)
        try {
            feeder.setActive(true)
            await(fifth)
            assertEquals(listOf(0 to 4_800, 0 to 4_800, 1_000 to 3_800, 3_000 to 1_800, 0 to 4_800), track.calls.toList())
            feeder.release()
            await(track.released)
            assertTrue(failures.isEmpty())
        } finally { feeder.release() }
    }

    @Test fun rapidOffAndOnWaitsForOldTrackReleaseBeforeOpeningTheNext() {
        val closing = CountDownLatch(1)
        val releaseOld = Semaphore(0)
        val old = object : Track() {
            override fun release() {
                closing.countDown()
                releaseOld.acquireUninterruptibly()
                super.release()
            }
        }
        val next = Track()
        val opened = AtomicInteger()
        val failures = CopyOnWriteArrayList<Exception>()
        val feeder = UsbSilenceFeeder({ if (opened.incrementAndGet() == 1) old else {
            check(old.released.count == 0L); next
        } }, failures::add)
        try {
            feeder.setActive(true); await(old.entered)
            feeder.setActive(false); feeder.setActive(true)
            await(closing)
            assertEquals(1, opened.get())
            releaseOld.release()
            await(next.entered)
            feeder.release(); await(next.released)
            assertEquals(2, opened.get())
            assertTrue(failures.isEmpty())
        } finally { releaseOld.release(); feeder.release() }
    }

    @Test fun closingWhileTheFactoryIsOpeningReleasesWithoutPlayingOrWriting() {
        val opening = CountDownLatch(1)
        val finishOpen = Semaphore(0)
        val track = Track()
        val feeder = UsbSilenceFeeder({ opening.countDown(); finishOpen.acquireUninterruptibly(); track }, {})
        try {
            feeder.setActive(true); await(opening)
            feeder.release()
            finishOpen.release()
            await(track.released)
            assertEquals(0, track.playing.get())
            assertTrue(track.calls.isEmpty())
            feeder.setActive(true)
            assertEquals(0, track.playing.get())
        } finally { finishOpen.release(); feeder.release() }
    }

    @Test fun deadAudioTrackIsReleasedAndRecreatedWhileStillEnabled() {
        val dead = object : Track() {
            override fun consume(samples: ShortArray, offset: Int, count: Int) = -6
        }
        val replacement = Track()
        val opened = AtomicInteger()
        val failures = CopyOnWriteArrayList<Exception>()
        val feeder = UsbSilenceFeeder({ if (opened.incrementAndGet() == 1) dead else {
            check(dead.released.count == 0L); replacement
        } }, failures::add)
        try {
            feeder.setActive(true)
            await(replacement.entered)
            assertEquals(2, opened.get())
            assertEquals(1, failures.size)
            feeder.release(); await(replacement.released)
        } finally { feeder.release() }
    }
}
