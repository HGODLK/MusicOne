package com.musicone

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private data class AiChatTailLayout(val count: Int, val lastIndex: Int?, val lastOffset: Int?, val lastSize: Int?,
    val end: Int, val padding: Int, val scrolling: Boolean, val following: Boolean)

/** 只有用户停在底部时才跟着流式输出自动往下滚，整个流式过程复用同一段连续滚动动画。 */
@Composable
internal fun rememberAiChatAutoFollow(list: LazyListState, firstMessageId: String? = null): Modifier {
    val intent = remember(list) { AiChatFollowIntent() }
    var followJob by remember(list) { mutableStateOf<Job?>(null) }
    var previousFirst by remember(list) { mutableStateOf(firstMessageId) }
    SideEffect {
        // 往上翻加载更多历史消息后停在当前阅读位置，别因为列表变长把用户拉回底部。
        if (previousFirst != null && firstMessageId != null && previousFirst != firstMessageId) {
            intent.readEarlier()
            followJob?.cancel()
        }
        previousFirst = firstMessageId
    }
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    fun nearBottom(): Boolean {
        if (!list.canScrollForward) return true
        val layout = list.layoutInfo
        val last = layout.visibleItemsInfo.lastOrNull() ?: return false
        return last.index == layout.totalItemsCount - 1 &&
            last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset <= threshold
    }
    val connection = remember(list, threshold) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // 用户一往上划就立刻停止自动跟随；代码触发的补滚不走 UserInput 分支。
            if (source == NestedScrollSource.UserInput && available.y != 0f) {
                intent.userScroll(available.y)
                followJob?.cancel()
            }
            return Offset.Zero
        }
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && consumed.y < 0f) intent.reachedTail(nearBottom())
            return Offset.Zero
        }
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            // 只有明确向底部的惯性滑动才恢复跟随；轻微向上翻阅释放时不能被误判为到达底部。
            intent.reachedTail(nearBottom())
            return Velocity.Zero
        }
    } }
    LaunchedEffect(list) {
        snapshotFlow {
            val layout = list.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            AiChatTailLayout(layout.totalItemsCount, last?.index, last?.offset, last?.size,
                layout.viewportEndOffset, layout.afterContentPadding, list.isScrollInProgress, intent.following)
        }.collect { tail ->
            if (!tail.following) followJob?.cancel()
            if (!tail.following || !intent.following || tail.scrolling || list.isScrollInProgress || tail.count == 0) return@collect
            if (followJob?.isActive == true || list.aiChatTailDistance() <= 0f) return@collect
            followJob = launch {
                // 在同一次 list.scroll 里持续追底，用户一碰屏幕就会按默认手势优先级直接打断。
                list.scroll {
                    val motion = AiChatFollowMotion()
                    var previousFrame = withFrameNanos { it }
                    while (intent.following && list.canScrollForward) {
                        val moved = withFrameNanos { now ->
                            // 每帧按最新的底部距离继续往前滚，保留当前速度，不会每来一个新字就重启一次动画。
                            val distance = list.aiChatTailDistance()
                            val delta = motion.advance(distance, now - previousFrame, musicSpring(visibilityThreshold = .5f))
                            previousFrame = now
                            scrollBy(delta)
                        }
                        if (moved == 0f) break
                    }
                }
            }
        }
    }
    return Modifier.nestedScroll(connection)
}

private fun LazyListState.aiChatTailDistance(): Float {
    if (!canScrollForward) return 0f
    val layout = layoutInfo
    val last = layout.visibleItemsInfo.lastOrNull() ?: return 0f
    if (last.index != layout.totalItemsCount - 1) {
        // 末项尚未进入视口时逐屏追赶，不能跳过屏外消息后再补动画。
        return (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(0).toFloat()
    }
    return (last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset).coerceAtLeast(0).toFloat()
}
