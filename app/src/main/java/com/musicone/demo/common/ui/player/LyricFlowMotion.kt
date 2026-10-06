package com.musicone.demo

import kotlin.math.exp

internal data class LyricFlowSample(val position: Float, val velocity: Float)

/** 连点时持续追踪新目标，不为每首重启计时；解析式推进保证不同刷新率下轨迹一致。 */
internal fun advanceLyricFlow(sample: LyricFlowSample, target: Float, seconds: Float): LyricFlowSample {
    if (seconds <= 0f) return sample
    val response = 12f
    val distance = sample.position - target
    val tangent = sample.velocity + response * distance
    val decay = exp(-response * seconds)
    val position = target + (distance + tangent * seconds) * decay
    val velocity = (sample.velocity - response * tangent * seconds) * decay
    // 接管已有高速运动时也不穿过最终页后再倒滚。
    return if (distance != 0f && (position - target) * distance < 0f) {
        LyricFlowSample(target, 0f)
    } else LyricFlowSample(position, velocity)
}
