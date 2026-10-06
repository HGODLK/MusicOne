package com.musicone.demo

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/** 由歌词统一帧循环采样；改目标时保留当前值和速度，不重新等待零进度帧。 */
internal class LyricMotionValue(initialValue: Float) {
    var value by mutableFloatStateOf(initialValue); private set
    var velocity = 0f; private set
    private var target = initialValue
    private var spec: FiniteAnimationSpec<Float>? = null
    private var animation: TargetBasedAnimation<Float, AnimationVector1D>? = null
    private var playTimeNanos = 0L
    val running: Boolean get() = animation != null

    fun retarget(target: Float, spec: FiniteAnimationSpec<Float>) {
        if (this.target == target && this.spec == spec) return
        this.target = target
        this.spec = spec
        playTimeNanos = 0L
        animation = if (value == target && velocity == 0f) null else TargetBasedAnimation(
            animationSpec = spec,
            typeConverter = Float.VectorConverter,
            initialValue = value,
            targetValue = target,
            initialVelocityVector = AnimationVector1D(velocity),
        )
    }

    fun advance(deltaNanos: Long, finish: Boolean = false) {
        val motion = animation ?: return
        playTimeNanos = if (finish) motion.durationNanos else
            (playTimeNanos + deltaNanos).coerceAtMost(motion.durationNanos)
        value = motion.getValueFromNanos(playTimeNanos)
        velocity = motion.getVelocityVectorFromNanos(playTimeNanos).value
        if (playTimeNanos >= motion.durationNanos) {
            value = target
            velocity = 0f
            animation = null
        }
    }

    fun snapTo(value: Float, velocity: Float = 0f) {
        this.value = value
        this.velocity = velocity
        target = value
        animation = null
        spec = null
    }
}
