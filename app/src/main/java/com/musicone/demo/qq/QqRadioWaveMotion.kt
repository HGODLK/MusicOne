package com.musicone.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/** 声波相位按速度曲线积分，低帧率、暂停和反向请求都从当前相位与相位速度接续。 */
internal class QqRadioWaveMotion(playing: Boolean, initialPhase: Float = 0f) {
    var phase by mutableFloatStateOf(initialPhase)
        private set
    var velocity by mutableFloatStateOf(if (playing) CRUISE_SPEED else 0f)
        private set
    private var target = velocity
    private var startVelocity = velocity
    private var elapsed = 0.0
    private var duration = 0.0
    val running get() = velocity > 0f || target > 0f

    fun request(playing: Boolean) {
        val next = if (playing) CRUISE_SPEED else 0f
        if (next == target) return
        startVelocity = velocity
        target = next
        elapsed = 0.0
        duration = if (playing) .420 else .520
    }

    fun advance(seconds: Double) {
        var remaining = seconds.coerceAtLeast(0.0)
        var travel = 0.0
        if (elapsed < duration) {
            val step = minOf(remaining, duration - elapsed)
            val before = elapsed / duration
            elapsed += step
            val after = elapsed / duration
            val change = target - startVelocity
            travel = startVelocity * step + change * duration * (integral(after) - integral(before))
            velocity = (startVelocity + change * smooth(after)).toFloat()
            remaining -= step
        }
        travel += target * remaining
        phase = ((phase.toDouble() + travel) % 360.0).toFloat()
    }

    // 不可见和减弱动态时只同步相位速度，返回时不补转离开期间的相位。
    fun settle(playing: Boolean) {
        target = if (playing) CRUISE_SPEED else 0f
        velocity = target
        startVelocity = target
        elapsed = 0.0
        duration = 0.0
    }

    private fun smooth(t: Double) = t * t * (3.0 - 2.0 * t)
    private fun integral(t: Double) = t * t * t - .5 * t * t * t * t

    companion object { const val CRUISE_SPEED = 20f }
}
