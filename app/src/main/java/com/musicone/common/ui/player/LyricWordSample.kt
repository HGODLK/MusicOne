package com.musicone

import kotlin.math.abs
import kotlin.math.exp

/** 词级状态原位更新；公式、阈值和越界处理与原解析式逐字弹簧一致。 */
internal class LyricWordSample(var position: Float, var velocity: Float = 0f) {
    fun moving(target: Float): Boolean = abs(position - target) > .0001f || abs(velocity) > .002f

    fun follow(target: Float, seconds: Float, finish: Boolean): Boolean {
        val previous = position
        if (finish || !moving(target)) {
            position = target
            velocity = 0f
        } else if (seconds > 0f) {
            val distance = position - target
            val tangent = velocity + 22f * distance
            val decay = exp(-22f * seconds)
            val next = target + (distance + tangent * seconds) * decay
            val speed = (velocity - 22f * tangent * seconds) * decay
            if (distance != 0f && (next - target) * distance < 0f) {
                position = target
                velocity = 0f
            } else {
                position = next.coerceIn(0f, 1f)
                velocity = if (position != next) 0f else speed
            }
        }
        return previous != position
    }
}
