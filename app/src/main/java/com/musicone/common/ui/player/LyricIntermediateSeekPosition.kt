package com.musicone

import androidx.compose.foundation.MutatePriority
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

internal enum class LyricIntermediateSeekResult { ALIGNED, FAR, RELEASED }

/** 歌词页开合中途的 seek 对齐：窗口继续开合，列表用弹簧平滑滚回阅读锚点，途中换句只更新终点。 */
internal suspend fun alignIntermediateLyricSeek(
    pane: LyricWindowPane,
    target: () -> Int,
    held: () -> Boolean,
    owned: () -> Boolean,
    transitioning: () -> Boolean,
    canRelease: () -> Boolean,
    windowOffset: () -> Float = { 0f },
    windowVelocity: () -> Float = { 0f },
): LyricIntermediateSeekResult {
    pane.playbackStep.takeOverSeek(target())
    var result = LyricIntermediateSeekResult.RELEASED
    pane.listState.scroll(MutatePriority.PreventUserInput) {
        val travel = LyricIntermediateSeekTravel()
        var previous = withFrameNanos { it }
        while (owned()) {
            val frame = withFrameNanos { it }
            val elapsed = (frame - previous).coerceAtLeast(0L)
            previous = frame
            // 手指按住期间暂停推进弹簧，松手后从原位置和速度继续。
            if (held()) continue
            val index = target()
            val layout = pane.listState.layoutInfo
            val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
            if (item == null && !shouldAnimateLyricScroll(pane.listState.firstVisibleItemIndex, index)) {
                if (transitioning()) continue
                result = LyricIntermediateSeekResult.FAR
                break
            }
            val nearest = item ?: layout.visibleItemsInfo.minByOrNull { abs(it.index - index) } ?: continue
            val offset = item?.offset?.toFloat() ?: (nearest.offset +
                (index - nearest.index) * (nearest.size + layout.mainAxisItemSpacing)).toFloat()
            val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
            travel.advance(index, offset, windowOffset(), windowVelocity(),
                if (scale > 0f) (elapsed / scale).toLong() else 0L,
                finish = scale == 0f, scrollBy = { scrollBy(it) })
            val actual = pane.listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target() }
            val atBoundary = actual != null && (actual.offset > 0 && !pane.listState.canScrollForward ||
                actual.offset < 0 && !pane.listState.canScrollBackward)
            if (actual != null && target() == index && !travel.running && !transitioning() && canRelease()) {
                if (actual.offset == 0 || atBoundary) {
                    result = LyricIntermediateSeekResult.ALIGNED
                    break
                }
                // 列表像素取整可能残留 1px 误差，继续用同一弹簧微调到位，不生硬跳变。
                travel.refine(actual.offset.toFloat())
            }
        }
    }
    return result
}

/** 用临界阻尼弹簧驱动列表对齐，通过累计已滚动距离消除整数像素舍入造成的抖动。 */
internal class LyricIntermediateSeekTravel {
    private val motion = LyricMotionValue(0f)
    private var applied = 0f
    private var index = -1
    private var endpoint = 0f
    private var initialWindow: Float? = null
    private var currentWindow = 0f
    val running get() = motion.running
    val velocity get() = motion.velocity

    fun advance(index: Int, offset: Float, windowOffset: Float, windowVelocity: Float,
        elapsed: Long, finish: Boolean,
        scrollBy: (Float) -> Float) {
        if (initialWindow == null) {
            initialWindow = windowOffset
            // 首帧继承窗口松手时的速度，避免列表和窗口反向拉扯。
            motion.snapTo(0f, -windowVelocity)
        }
        currentWindow = windowOffset
        val origin = initialWindow!!
        val destination = lyricSeekVisualTarget(applied, offset, origin)
        val retargetThreshold = if (abs(offset + windowOffset) <= 8f) .25f else 1f
        if (this.index != index || abs(destination - endpoint) > retargetThreshold) {
            this.index = index
            endpoint = destination
            retarget(destination, offset + windowOffset)
        }
        motion.advance(elapsed, finish)
        val requested = lyricSeekScrollRequest(motion.value, windowOffset, origin, applied)
        val consumed = scrollBy(requested)
        applied += consumed
        if (abs(requested - consumed) > .5f) {
            // 滚到列表边界滚不动时，按实际滚动量修正弹簧位置但保留速度，避免被强行刹停。
            val velocity = motion.velocity
            motion.snapTo(applied - (windowOffset - origin), velocity)
            endpoint = motion.value
        }
    }

    fun refine(itemOffset: Float) {
        val origin = initialWindow ?: return
        endpoint = applied + itemOffset + origin - currentWindow
        retarget(endpoint, itemOffset)
    }

    private fun retarget(destination: Float, distance: Float) {
        val stiffness = lyricPlaybackMotionStiffness(distance, LyricPlaybackMotionPurpose.ALIGNMENT)
        motion.retarget(destination, lyricPlaybackMotionSpec(distance, LyricPlaybackMotionPurpose.ALIGNMENT),
            initialVelocity = lyricWindowSettleVelocity(motion.velocity, destination - motion.value, stiffness))
    }
}

internal fun lyricSeekVisualTarget(applied: Float, itemOffset: Float, initialWindow: Float): Float =
    applied + itemOffset + initialWindow

internal fun lyricSeekScrollRequest(visualMotion: Float, windowOffset: Float,
    initialWindow: Float, applied: Float): Float =
    visualMotion + windowOffset - initialWindow - applied
