package com.musicone

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter

/** 复用通用的临界阻尼弹簧逐帧步进，目标变远时接着当前速度继续滚，不重新起步。 */
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
