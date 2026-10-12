package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.flow.first
import kotlin.coroutines.coroutineContext

/** 中间态连续 seek 可暂存双窗口位置；松手沿同一弹簧的当前值与速度续接。 */
internal suspend fun runLyricHeldLayerTravel(offset: Animatable<Float, *>, travel: Float,
    purpose: LyricPlaybackMotionPurpose, held: () -> Boolean) {
    val motion = LyricMotionValue(travel)
    motion.retarget(0f, lyricPlaybackMotionSpec(travel, purpose))
    var previous = withFrameNanos { it }
    while (motion.running) {
        if (held()) {
            snapshotFlow { held() }.first { !it }
            previous = withFrameNanos { it }
        }
        val frame = withFrameNanos { it }
        val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
        if (!held()) {
            motion.advance(if (scale > 0f) ((frame - previous).coerceAtLeast(0L) / scale).toLong() else 0L,
                finish = scale == 0f)
            offset.snapTo(motion.value)
        }
        previous = frame
    }
}
