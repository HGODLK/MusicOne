package com.musicone

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter

/** 复用现有临界阻尼弹簧与帧采样方式，目标变化时从当前速度继续补滚。 */
internal class AiChatFollowMotion {
    var velocity = 0f
        private set

    fun advance(remaining: Float, deltaNanos: Long, spec: FiniteAnimationSpec<Float>): Float {
        if (remaining <= 0f) {
            velocity = 0f
            return 0f
        }
        if (deltaNanos <= 0L) return 0f
        val motion = TargetBasedAnimation(
            animationSpec = spec,
            typeConverter = Float.VectorConverter,
            initialValue = 0f,
            targetValue = remaining,
            initialVelocityVector = AnimationVector1D(velocity),
        )
        val delta = motion.getValueFromNanos(deltaNanos).coerceIn(0f, remaining)
        velocity = if (delta >= remaining) 0f else motion.getVelocityVectorFromNanos(deltaNanos).value.coerceAtLeast(0f)
        return delta
    }
}
