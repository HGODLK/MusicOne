package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** 松手时先建立零时刻，下一次真实动画帧直接推进，保留原弹簧、边界和取消行为。 */
internal suspend fun Animatable<Float, AnimationVector1D>.animateToWithGestureHandoff(
    target: Float,
    animationSpec: AnimationSpec<Float>,
    initialVelocity: Float = velocity,
    releaseTimeNanos: Long? = null,
    nowNanos: () -> Long = System::nanoTime,
    block: (Animatable<Float, AnimationVector1D>.() -> Unit)? = null,
) {
    if (releaseTimeNanos == null) {
        animateTo(target, animationSpec, initialVelocity, block)
        return
    }
    val releaseNanos = checkNotNull(releaseTimeNanos)
    val upstream = checkNotNull(coroutineContext[MonotonicFrameClock])
    val handoffClock = object : MonotonicFrameClock {
        private var seeded = false
        private var previousTime = releaseNanos

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            if (!seeded) {
                seeded = true
                return onFrame(releaseNanos)
            }
            // 输入可在本帧起始之后才处理；按实际回调时刻推进，不能再提交一次零进度。
            return upstream.withFrameNanos {
                previousTime = maxOf(previousTime, it, nowNanos())
                onFrame(previousTime)
            }
        }
    }
    withContext(handoffClock) { animateTo(target, animationSpec, initialVelocity, block) }
}
