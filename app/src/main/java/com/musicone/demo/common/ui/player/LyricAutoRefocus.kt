package com.musicone.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlin.math.abs

internal data class LyricAutoRefocusTarget(val index: Int, val offset: Float)

/** 只持有屏内自动回焦；用户操作和其他定位事务可立即接管。 */
internal class LyricAutoRefocus {
    var active by mutableStateOf(false)
        private set
    private var revision = 0L
    private var job: Job? = null

    fun cancel() {
        revision++
        job?.cancel()
        job = null
        active = false
    }

    suspend fun align(pane: LyricWindowPane, current: () -> Int, allowed: () -> Boolean): Int? = run {
        pane.playbackStep.reset()
        var completed: Int? = null
        pane.listState.scroll {
            completed = settle({
                val index = current()
                if (!isLyricTargetTrulyVisible(pane.listState, index)) null else
                    pane.listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                        ?.let { LyricAutoRefocusTarget(index, lyricVisibleSeekTravel(it.offset)) }
            }, { scrollBy(it) }, allowed)
        }
        completed?.takeIf { allowed() }?.also {
            // 只确认实际到位的句子，不清空行内强调和位置动画。
            pane.playbackStep.consume(it, enabled = true)
        }
    }

    internal suspend fun run(operation: suspend () -> Int?): Int? = coroutineScope {
        val request = ++revision
        job = currentCoroutineContext()[Job]
        active = true
        try { operation() } finally {
            if (revision == request) { active = false; job = null }
        }
    }

    internal suspend fun settle(target: () -> LyricAutoRefocusTarget?, scrollBy: (Float) -> Float,
        allowed: () -> Boolean): Int? {
        if (!allowed()) return null
        val travel = LyricAutoRefocusTravel()
        var previous = withFrameNanos { it }
        while (allowed()) {
            val next = target() ?: return null
            travel.retarget(next)
            if (!travel.running) {
                // 仅收掉整数像素舍入余量；边界或布局变化不能被当成成功到位。
                if (abs(next.offset) > 1f) return null
                if (next.offset != 0f) scrollBy(next.offset)
                val final = target() ?: return null
                return final.index.takeIf { it == next.index && abs(final.offset) <= .5f && allowed() }
            }
            val frame = withFrameNanos { it }
            if (!allowed()) return null
            travel.retarget(target() ?: return null)
            val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
            if (!travel.advance(if (scale > 0f) ((frame - previous).coerceAtLeast(0L) / scale).toLong() else 0L,
                    scale == 0f, scrollBy)) return null
            previous = frame
        }
        return null
    }
}

/** 用累计实际消费量换算目标；换句时保留现有位移和速度。 */
internal class LyricAutoRefocusTravel {
    internal val motion = LyricMotionValue(0f)
    private var applied = 0f
    private var index = -1
    private var endpoint = 0f
    val running get() = motion.running

    fun retarget(target: LyricAutoRefocusTarget) {
        val destination = applied + target.offset
        if (index != target.index || abs(destination - endpoint) > 1.5f) {
            index = target.index
            endpoint = destination
            motion.retarget(destination, lyricPlaybackMotionSpec(target.offset, LyricPlaybackMotionPurpose.ALIGNMENT))
        }
    }

    fun advance(elapsed: Long, finish: Boolean, scrollBy: (Float) -> Float): Boolean {
        motion.advance(elapsed, finish)
        val requested = motion.value - applied
        val consumed = scrollBy(requested)
        applied += consumed
        return abs(requested - consumed) <= .5f
    }
}
