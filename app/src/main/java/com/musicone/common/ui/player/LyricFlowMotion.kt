package com.musicone

import kotlin.math.exp

internal data class LyricFlowSample(val position: Float, val velocity: Float)

/** 切歌连续滑动动画：连点切歌时无缝改追新目标，解析式推进保证不同刷新率下轨迹一致。 */
internal fun advanceLyricFlow(
    sample: LyricFlowSample, target: Float, seconds: Float, response: Float = 12f,
): LyricFlowSample {
    if (seconds <= 0f) return sample
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
