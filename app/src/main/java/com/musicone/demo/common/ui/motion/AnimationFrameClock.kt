package com.musicone.demo

import androidx.compose.runtime.MonotonicFrameClock
import kotlin.math.floor

internal enum class AnimationFrameRate(val fps: Int, val label: String) {
    FPS_30(30, "30 帧"), FPS_60(60, "60 帧"), FPS_90(90, "90 帧"), FPS_120(120, "120 帧"),
    DISPLAY(0, "跟随屏幕刷新率");

    companion object {
        fun fromStored(fps: Int): AnimationFrameRate = entries.firstOrNull { it.fps == fps } ?: DISPLAY
    }
}

/** 同一屏幕帧的所有动画统一放行；按累计期限取帧，避免 90/120 等比例退化为 60 帧。 */
internal class AnimationFrameSchedule {
    private var lastFrame = Long.MIN_VALUE
    private var lastRate = -1
    private var accepted = false
    private var nextFrame = 0.0

    fun accept(frameNanos: Long, fps: Int): Boolean {
        if (lastFrame == frameNanos && lastRate == fps) return accepted
        val changed = fps != lastRate
        lastFrame = frameNanos
        lastRate = fps
        if (fps <= 0) { accepted = true; return true }
        val interval = 1_000_000_000.0 / fps
        if (changed) nextFrame = frameNanos.toDouble()
        accepted = frameNanos + 1_000.0 >= nextFrame
        if (accepted) {
            // 漏帧和息屏恢复只跳过过期期限，不补发旧帧或改写动画时间。
            nextFrame += (floor((frameNanos + 1_000.0 - nextFrame) / interval) + 1) * interval
        }
        return accepted
    }
}

internal class AnimationFrameClock(
    private val upstream: MonotonicFrameClock,
    private val frameRate: () -> AnimationFrameRate,
    private val awaitActive: suspend () -> Unit = {},
) : MonotonicFrameClock {
    private val schedule = AnimationFrameSchedule()

    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
        while (true) {
            awaitActive()
            var delivered = false
            var result: R? = null
            upstream.withFrameNanos { time ->
                if (schedule.accept(time, frameRate().fps)) {
                    // 回调仍在系统帧的分发阶段执行，不能推迟到绘制完成之后。
                    result = onFrame(time)
                    delivered = true
                }
            }
            if (delivered) {
                @Suppress("UNCHECKED_CAST")
                return result as R
            }
        }
    }
}
