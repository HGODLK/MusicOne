package com.musicone

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** 带手势松手时间戳衔接的 animateTo：把松手瞬间作为第 0 帧，下一系统帧直接推进，减少松手首帧停顿。 */
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
            // 输入事件时间可能略晚于当前帧开始时间，取较大值确保时间单调前进。
            return upstream.withFrameNanos {
                previousTime = maxOf(previousTime, it, nowNanos())
                onFrame(previousTime)
            }
        }
    }
    withContext(handoffClock) { animateTo(target, animationSpec, initialVelocity, block) }
}
