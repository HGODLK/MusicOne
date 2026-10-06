package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/** 所有切歌窗口共用位移和速度，相邻页按完整出屏距离排列，不叠加整窗淡出。 */
internal class LyricWindowMotion {
    val offset = Animatable(0f, visibilityThreshold = .0005f)
    private var visibilityThreshold = .0005f
    private var velocity = 0f
    private var animation: Job? = null
    private var requestedSlot: Float? = null
    private var browsing = false
    private var revision = 0L
    private var running by mutableStateOf(false)
    val isRunning: Boolean get() = running || offset.isRunning

    // 归一化位移按真实出屏距离换算精度，避免大屏最后一帧直接补跳一两个像素。
    fun updateTravel(travelPx: Float) {
        visibilityThreshold = .05f / travelPx.coerceAtLeast(1f)
    }

    // 单次与自然切歌保持原曲线；快切只更新连续运动目标，停手后从当前速度收束。
    fun request(scope: CoroutineScope, slot: Float, browsing: Boolean = false) {
        if (requestedSlot == slot && this.browsing == browsing && animation?.isCancelled != true) return
        requestedSlot = slot
        if (this.browsing && browsing && animation?.isActive == true) return
        this.browsing = browsing
        val requestRevision = ++revision
        animation?.cancel()
        running = true
        animation = scope.launch {
            try {
                if (browsing) flow() else moveTo(slot)
            } finally {
                if (revision == requestRevision) running = false
            }
        }
    }

    private suspend fun flow() {
        var previousFrame = withFrameNanos { it }
        while (currentCoroutineContext().isActive) {
            val frame = withFrameNanos { it }
            val target = -(requestedSlot ?: return)
            val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
            val sample = if (ExperiencePreferences.options.reduceMotion || scale == 0f) {
                LyricFlowSample(target, 0f)
            } else advanceLyricFlow(LyricFlowSample(offset.value, velocity), target,
                (frame - previousFrame).coerceAtLeast(0L) / 1_000_000_000f / scale)
            previousFrame = frame
            velocity = sample.velocity
            offset.snapTo(sample.position)
        }
    }

    suspend fun moveTo(slot: Float) {
        val target = -slot
        offset.animateTo(target,
            musicSpring(210f, visibilityThreshold = visibilityThreshold),
            initialVelocity = lyricWindowSettleVelocity(velocity, target - offset.value)) {
            this@LyricWindowMotion.velocity = this.velocity
        }
        velocity = 0f
    }

    suspend fun show(slot: Float) {
        ++revision
        animation?.cancelAndJoin()
        running = false
        browsing = false
        requestedSlot = null
        offset.snapTo(-slot)
        velocity = 0f
    }

    fun settledAt(slot: Float): Boolean = !isRunning && abs(offset.value + slot) <= visibilityThreshold
}

/** 临近终点时限制制动距离，避免快切速度越过目标后又滚回来；正常中途续接仍保留速度。 */
internal fun lyricWindowSettleVelocity(velocity: Float, distance: Float): Float {
    if (distance == 0f) return 0f
    if (velocity * distance <= 0f) return velocity
    val limit = abs(distance) * sqrt(210f)
    return velocity.coerceIn(-limit, limit)
}

/** 只移除沿本次切歌方向完整离场的窗口，不能把尚在另一侧等待进入的窗口提前删掉。 */
internal fun lyricWindowOutside(offset: Float, direction: TrackTransitionDirection): Boolean =
    if (direction == TrackTransitionDirection.NEXT) offset <= -1f else offset >= 1f
