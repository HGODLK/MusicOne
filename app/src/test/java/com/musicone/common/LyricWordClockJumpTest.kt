package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricWordClockJumpTest {
    private var now = 0L
    private val jump = LyricWordClockJump { now }
    private fun step(ms: Long, position: Long?): Boolean {
        now += ms * 1_000_000L
        return jump.update(position)
    }

    @Test fun 正常播放小幅校正和卡顿都不算跳转() {
        assertFalse(step(0, 0L))
        assertFalse(step(16, 16L))
        assertFalse(step(16, 280L))
        assertFalse(step(1_000, 1_280L))
    }

    @Test fun 暂停后恢复不算跳转() {
        assertFalse(step(0, 5_000L))
        assertFalse(step(30_000, 5_016L))
    }

    @Test fun 前后跳转都能识别并且重绑后重新开始() {
        assertFalse(step(0, 10_000L))
        assertTrue(step(16, 40_000L))
        assertTrue(step(16, 2_000L))
        assertFalse(step(16, null))
        assertFalse(step(16, 90_000L))
    }

    private val ranges = listOf(LyricHighlightRange(0, 1, LyricWord(100, 400, "光")))

    @Test fun 同一句内跳转直接到位() {
        var position = 100L
        val motion = LyricWordLightMotion()
        motion.configure(ranges, { position }, true, true, true)
        position = 250L
        motion.snapToClock()
        assertTrue(motion.running)
        motion.advance(1f / 60)
        assertEquals(.85f, motion.alpha(0), .00001f)
        assertFalse(motion.running)
    }

    @Test fun 进场窗口按旧进度初始化后直接校正不保持() {
        var position = 250L
        val motion = LyricWordLightMotion()
        motion.entering = true
        motion.configure(ranges, { position }, true, false, true)
        // 落点移到这句之后：新窗口不能保持旧亮度，也不能慢慢追赶。
        position = 60_000L
        motion.snapToClock()
        motion.advance(1f / 60)
        assertEquals(1f, motion.alpha(0), .00001f)
        // 没被判定成跳转的进度变化同样直接到位。
        position = 0L
        motion.advance(1f / 60)
        assertTrue(motion.alpha(0) < 1f)
        val settled = motion.alpha(0)
        motion.advance(1f / 60)
        assertEquals(settled, motion.alpha(0), 0f)
    }

    @Test fun 进场结束后恢复旧句保持() {
        var position = 250L
        val motion = LyricWordLightMotion()
        motion.entering = true
        motion.configure(ranges, { position }, true, false, true)
        motion.entering = false
        val before = motion.alpha(0)
        position = 60_000L
        motion.snapToClock()
        motion.advance(1f / 60)
        assertEquals(before, motion.alpha(0), 0f)
    }

    @Test fun 跳走时旧句保持原样不填满之后再柔和跟上() {
        var position = 250L
        val motion = LyricWordLightMotion()
        motion.configure(ranges, { position }, true, true, true)
        val before = motion.alpha(0)
        motion.configure(ranges, { position }, true, false, true, allowTail = true)
        assertTrue(motion.finishingTail)
        position = 60_000L
        motion.snapToClock()
        assertFalse(motion.finishingTail)
        repeat(20) { motion.advance(1f / 60) }
        assertEquals(before, motion.alpha(0), 0f)
        // 离开逐字范围后强度也不能在保持期内清零，否则整句同样变亮。
        motion.configure(ranges, { position }, false, false, true)
        repeat(5) { motion.advance(1f / 60) }
        assertEquals(before, motion.alpha(0), 0f)
        repeat(120) { motion.advance(1f / 60) }
        assertEquals(1f, motion.alpha(0), .00001f)
        assertFalse(motion.running)
    }

    @Test fun 保持期内成为当前句的行立刻校正() {
        var position = 1_000L
        val motion = LyricWordLightMotion()
        motion.configure(ranges, { position }, true, false, true)
        position = 0L
        motion.snapToClock()
        motion.configure(ranges, { position }, true, true, true)
        motion.advance(1f / 60)
        assertEquals(.7f, motion.alpha(0), .00001f)
    }

    @Test fun 还没换句时重复配置旧句不会解除保持() {
        var position = 250L
        val motion = LyricWordLightMotion()
        motion.configure(ranges, { position }, true, true, true)
        val before = motion.alpha(0)
        position = 60_000L
        motion.snapToClock()
        motion.configure(ranges, { position }, true, true, true)
        motion.advance(1f / 60)
        assertEquals(before, motion.alpha(0), 0f)
    }

    @Test fun 退场窗口停在原样且复用时校正到当前进度() {
        var position = 250L
        val motion = LyricWordLightMotion()
        motion.configure(ranges, { position }, true, true, true)
        val before = motion.alpha(0)
        motion.hold()
        position = 60_000L
        assertFalse(motion.running)
        assertNull(motion.observedPosition())
        motion.advance(1f)
        assertEquals(before, motion.alpha(0), 0f)
        position = 250L
        motion.configure(ranges, { position }, true, false, true)
        assertEquals(.85f, motion.alpha(0), .00001f)
    }

    @Test fun 跳转后直接成为当前句的行立刻显示明暗而非先全亮() {
        val motion = LyricWordLightMotion()
        motion.configure(ranges, { 0L }, false, false, true)
        assertEquals(1f, motion.alpha(0), 0f)
        motion.configure(ranges, { 0L }, true, true, true)
        assertEquals(.7f, motion.alpha(0), .00001f)
    }

    @Test fun 提前启用但未聚焦的行仍然柔和压暗() {
        val motion = LyricWordLightMotion()
        motion.configure(ranges, { 0L }, false, false, true)
        motion.configure(ranges, { 0L }, true, false, true)
        assertEquals(1f, motion.alpha(0), 0f)
        motion.advance(1f / 60)
        assertTrue(motion.alpha(0) < 1f)
    }

    @Test fun 点回已唱完的句子不会先亮后暗() {
        var position = 60_000L
        val motion = LyricWordLightMotion()
        motion.configure(ranges, { position }, true, false, true)
        assertEquals(1f, motion.alpha(0), .00001f)
        // 落点在句首预留区，时钟不在这句范围内，也不能走旧句保持。
        position = 0L
        motion.alignToClock()
        motion.advance(1f / 60)
        assertEquals(.7f, motion.alpha(0), .00001f)
        assertFalse(motion.running)
    }

    @Test fun 点击后才进入范围的下一句直接压暗() {
        var position = 60_000L
        val motion = LyricWordLightMotion()
        motion.configure(ranges, { position }, false, false, true)
        assertEquals(1f, motion.alpha(0), 0f)
        position = 0L
        motion.alignToClock()
        // 启用前的帧不能消耗掉这次校正。
        motion.advance(1f / 60)
        assertEquals(1f, motion.alpha(0), 0f)
        motion.configure(ranges, { position }, true, false, true)
        assertEquals(.7f, motion.alpha(0), .00001f)
    }

    @Test fun 没有点击时未启用的行不受跳转影响且启用后仍柔和压暗() {
        var position = 60_000L
        val motion = LyricWordLightMotion()
        motion.configure(ranges, { position }, false, false, true)
        position = 0L
        motion.snapToClock()
        motion.configure(ranges, { position }, true, false, true)
        assertEquals(1f, motion.alpha(0), 0f)
    }

    @Test fun 行模糊只按档位变化且关闭时为零档() {
        assertEquals(10, lyricRowBlurStep(2f, disabled = false))
        assertEquals(6, lyricRowBlurStep(1.05f, disabled = false))
        assertEquals(6, lyricRowBlurStep(.95f, disabled = false))
        // 靠近清晰的一端分档更细，最后一步落差最小。
        assertEquals(2, lyricRowBlurStep(.12f, disabled = false))
        assertEquals(1, lyricRowBlurStep(.04f, disabled = false))
        assertEquals(0, lyricRowBlurStep(.02f, disabled = false))
        assertEquals(0, lyricRowBlurStep(2f, disabled = true))
    }
}
