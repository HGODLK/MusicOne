package com.musicone

import kotlin.math.roundToLong

/**
 * 采样校正带来的小幅回退容差。起播时播放器已报告推进、声音尚未输出，推算时钟会先跑出一两百毫秒，
 * 下一次真实采样把它拉回；作词作曲等短句会因此跨回上一句、列表往回滑。容差内停住等真实时钟追上，
 * 超出容差仍视为跳转直接回退；应用内 seek 另由 seeking／resetKey 复位，不受容差影响。
 */
internal const val LYRIC_CLOCK_BACKWARD_TOLERANCE_MS = 500L

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
        val correcting = real < displayedMs && displayedMs - real <= LYRIC_CLOCK_BACKWARD_TOLERANCE_MS
        if (!initialized || key != resetKey || seeking || real < displayedMs && !correcting) {
            initialized = true
            key = resetKey
            held = holding
            lag.snapTo(0f)
            displayedMs = real
        } else if (holding) {
            held = true
            lag.snapTo(0f)
        } else if (correcting) {
            // 真实时钟小幅落后于已显示进度：停在原处等它追上，不往回走。
            held = false
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
