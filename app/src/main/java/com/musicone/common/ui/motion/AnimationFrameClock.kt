package com.musicone

import androidx.compose.runtime.MonotonicFrameClock
import kotlin.math.floor

internal enum class AnimationFrameRate(val fps: Int, val label: String) {
    FPS_30(30, "30 帧"), FPS_60(60, "60 帧"), FPS_90(90, "90 帧"), FPS_120(120, "120 帧"),
    DISPLAY(0, "跟随屏幕刷新率");

    companion object {
        fun fromStored(fps: Int): AnimationFrameRate = entries.firstOrNull { it.fps == fps } ?: DISPLAY
    }
}

/** 按目标帧率对系统帧做均匀抽帧，确保同一屏幕帧的所有动画统一放行，避免 90/120fps 掉成 60fps。 */
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
            // 遇到漏帧或息屏恢复时直接跳过已过期的时间片，不补发旧帧。
            nextFrame += (floor((frameNanos + 1_000.0 - nextFrame) / interval) + 1) * interval
        }
        return accepted
    }
}

internal class AnimationFrameClock(
    private val upstream: MonotonicFrameClock,
    private val frameRate: () -> Int,
    private val awaitActive: suspend () -> Unit = {},
    private val onFrameAccepted: (Long) -> Unit = {},
) : MonotonicFrameClock {
    private val schedule = AnimationFrameSchedule()

    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
        while (true) {
            awaitActive()
            var delivered = false
            var result: R? = null
            upstream.withFrameNanos { time ->
                if (schedule.accept(time, frameRate())) {
                    onFrameAccepted(time)
                    // 必须在系统 withFrameNanos 回调内直接执行 onFrame，不能推迟到帧外。
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
