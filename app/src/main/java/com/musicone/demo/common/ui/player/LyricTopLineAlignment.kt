package com.musicone.demo

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.collect
import kotlin.math.abs

/** 在屏外准备完整顶行，入场时由同一展开进度将列表送回阅读锚点。 */
internal suspend fun LazyListState.revealTopLineForEntrance() {
    scroll(MutatePriority.PreventUserInput) {
        val viewport = layoutInfo
        val delta = viewport.visibleItemsInfo.firstNotNullOfOrNull {
            lyricExitScrollDelta(it.offset, it.size, viewport.viewportStartOffset)
        } ?: 0f
        if (delta != 0f) scrollBy(delta)
    }
}

/** 保存列表交接状态，防止父级到终点的同一帧又启动播放跟随补位。 */
@Stable
internal class LyricTopLineAlignment {
    var pending by mutableStateOf(false)
        private set

    fun prepare() { pending = true }

    suspend fun align(list: LazyListState, current: () -> Int, progress: () -> Float) {
        pending = true
        list.scroll(MutatePriority.PreventUserInput) {
            val session = LyricEntranceAlignmentSession()
            snapshotFlow { current() to progress().coerceIn(0f, 1f) }.takeWhile { (index, fraction) ->
                val viewport = list.layoutInfo
                val item = viewport.visibleItemsInfo.firstOrNull { it.index == index }
                val nearest = item ?: viewport.visibleItemsInfo.minByOrNull { abs(it.index - index) }
                if (nearest != null) {
                    // 跨句先从真实位置接续；跨出视口时按邻近行推进，目标可见后改用实测行高。
                    val offset = item?.offset?.toFloat() ?: (nearest.offset +
                        (index - nearest.index) * (nearest.size + viewport.mainAxisItemSpacing)).toFloat()
                    session.moveTo(index, fraction, offset, measured = item != null) { scrollBy(it) }
                    if (fraction == 1f) {
                        // 同帧收掉列表整数像素余量，恢复跟随时已经处于最终锚点。
                        val finalItem = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                        if (finalItem != null) scrollBy(finalItem.offset.toFloat())
                    }
                }
                fraction < 1f
            }.collect()
        }
        // 展开尾帧若恰逢大跨度进度跳转，目标仍在屏外时直接准备该目标，不补播对齐弹簧。
        if (list.layoutInfo.visibleItemsInfo.none { it.index == current() }) list.scrollToItem(current())
        pending = false
    }
}

/** 接管时读取当前画面，不补播等待滚动权期间已经走过的展开进度。 */
internal class LyricEntranceAlignmentSession {
    private var target = -1
    private var measured = false
    private var travel: LyricEntranceTravel? = null

    fun moveTo(index: Int, progress: Float, offset: Float, measured: Boolean,
        scrollBy: (Float) -> Float) {
        if (target != index || travel == null || (!this.measured && measured)) {
            target = index
            this.measured = measured
            travel = LyricEntranceTravel(progress, offset)
        }
        travel!!.moveTo(progress, scrollBy)
    }
}

/** 反向、拖动和跨句重新接管时，起点始终是当前实际偏移。 */
internal class LyricEntranceTravel(private val start: Float, private val offset: Float) {
    private var applied = 0f

    fun moveTo(progress: Float, scrollBy: (Float) -> Float) {
        // 与收起对齐一样累计实际消费量，避免逐帧读取整数 offset 引入来回一像素抖动。
        applied += scrollBy(offset - remainingOffset(progress) - applied)
    }

    fun remainingOffset(progress: Float): Float = if (progress >= 1f || start >= 1f) 0f else {
        offset * ((1f - progress) / (1f - start)).coerceIn(0f, 1f)
    }
}

@Composable
internal fun rememberLyricTopLineAlignment(
    list: LazyListState,
    enabled: Boolean,
    active: Boolean,
    prepareWhileHidden: Boolean,
    openingAlignment: CompletableDeferred<Unit>?,
    exitAlignment: CompletableDeferred<Unit>?,
    current: Int,
    progress: () -> Float,
): LyricTopLineAlignment {
    val alignment = remember(list) { LyricTopLineAlignment() }
    val latestCurrent by rememberUpdatedState(current)
    val latestProgress by rememberUpdatedState(progress)
    LyricAnimationEffect(alignment, enabled, active, prepareWhileHidden, openingAlignment, exitAlignment) {
        if (enabled && active && !prepareWhileHidden && exitAlignment == null) {
            openingAlignment?.await()
            while (true) {
                snapshotFlow { alignment.pending || latestProgress() < 1f }.first { it }
                // 首次打开仍需等屏外定位完成；手势反向不重置列表或重播准备阶段。
                snapshotFlow { latestProgress() }.first { it > 0f }
                alignment.align(list, { latestCurrent }, { latestProgress() })
            }
        }
    }
    return alignment
}
