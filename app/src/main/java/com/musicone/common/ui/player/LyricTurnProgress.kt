package com.musicone

import kotlin.math.roundToLong

/** 转向只冻结显示时间；恢复时沿现有曲线消除时间差，不改变真实播放时钟。 */
internal class LyricTurnProgress {
    private val lag = LyricMotionValue(0f)
    private var initialized = false
    private var held = false
    private var key: Any? = null
    private var previousNanos = 0L
    private var displayedMs = 0L
    val presenting get() = held || lag.running
    val catchingUp get() = lag.running

    fun sample(realMs: Long, nowNanos: Long, holding: Boolean, resetKey: Any?,
        seeking: Boolean = false, reduceMotion: Boolean = false): Long {
        val real = realMs.coerceAtLeast(0L)
        val elapsed = (nowNanos - previousNanos).coerceAtLeast(0L)
        previousNanos = nowNanos
        if (!initialized || key != resetKey || seeking || real < displayedMs) {
            initialized = true
            key = resetKey
            held = holding
            lag.snapTo(0f)
            displayedMs = real
        } else if (holding) {
            held = true
            lag.snapTo(0f)
        } else if (held) {
            // 首个释放帧仍是刚才所见进度，真实时钟的继续前进随后一起进入追赶。
            held = false
            lag.snapTo((real - displayedMs).toFloat())
            lag.retarget(0f, musicMotion(240))
            if (reduceMotion) { lag.snapTo(0f); displayedMs = real }
        } else {
            lag.advance(elapsed, finish = reduceMotion)
            displayedMs = (real - lag.value.roundToLong()).coerceIn(displayedMs, real)
        }
        return displayedMs
    }
}
