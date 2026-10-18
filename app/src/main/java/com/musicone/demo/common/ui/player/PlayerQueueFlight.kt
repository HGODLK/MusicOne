package com.musicone.demo

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlin.math.exp
import kotlin.math.sqrt

/** 与 musicSpring 相同的临界阻尼；每帧接收新目标，不因手指滚动取消动画时钟。 */
@Stable
internal class PlayerQueueFlight(initial: Float) {
    var position by mutableFloatStateOf(initial)
        private set
    private var velocity = 0f

    fun advance(target: Float, seconds: Float, immediate: Boolean = false) {
        if (immediate) { position = target; velocity = 0f; return }
        val dt = seconds.coerceIn(0f, .05f)
        val frequency = sqrt(240f)
        val displacement = position - target
        val continuation = velocity + frequency * displacement
        val decay = exp(-frequency * dt)
        position = target + (displacement + continuation * dt) * decay
        velocity = (velocity - frequency * continuation * dt) * decay
        if (kotlin.math.abs(position - target) < .05f && kotlin.math.abs(velocity) < .05f) {
            position = target
            velocity = 0f
        }
    }
}

internal fun queueRowOutsideViewport(top: Float, height: Int, viewportHeight: Int): Boolean =
    top + height <= 0f || top >= viewportHeight
