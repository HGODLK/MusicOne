package com.musicone

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.flow.first
import kotlin.coroutines.coroutineContext

/** 开合中途跨屏 seek 时支持按住暂停双窗口位移，松手后保留当前位置和速度继续滑完。 */
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
