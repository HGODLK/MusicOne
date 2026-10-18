package com.musicone.demo

import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first

/** 封面手势独立登记归位请求，不能用句号是否变化代替实际到位。 */
internal class LyricGestureRefocus {
    var pending by mutableStateOf(false)
        private set
    private var held = false
    private var revision = 0L

    fun observe(gestureHeld: Boolean, active: Boolean) {
        if (gestureHeld && !held) { revision++; pending = true }
        if (!gestureHeld && !active) { revision++; pending = false }
        held = gestureHeld
    }

    fun request(): Long = revision
    fun complete(request: Long) { if (!held && request == revision) pending = false }
}

@Composable
internal fun LyricGestureRefocusEffect(
    request: LyricGestureRefocus,
    controller: LyricLayerTransitionController,
    refocus: LyricAutoRefocus,
    alignment: LyricTopLineAlignment,
    held: Boolean,
    active: Boolean,
    enabled: Boolean,
    current: Int,
    progress: () -> Float,
    onAligned: () -> Unit,
) {
    val latestCurrent by rememberUpdatedState(current)
    val latestProgress by rememberUpdatedState(progress)
    val latestAligned by rememberUpdatedState(onAligned)
    SideEffect { request.observe(held, active) }
    LyricAnimationEffect(request, controller, held, active, enabled, request.pending) {
        if (!request.pending || held || !active || !enabled) return@LyricAnimationEffect
        val revision = request.request()
        // 外层开合仍沿原曲线完成；列表随后从真实偏移寻焦，不在终点硬跳。
        snapshotFlow { latestProgress() >= 1f && controller.state == LyricLayerTransitionState.NORMAL }
            .first { it }
        alignment.acceptSeekAlignment()
        while (request.pending && request.request() == revision) {
            val pane = controller.currentPane
            val index = latestCurrent
            if (pane.listState.layoutInfo.visibleItemsInfo.any { it.index == index }) {
                val aligned = refocus.align(pane, { latestCurrent }, requireVisible = false) {
                    request.pending && controller.currentPane === pane
                }
                // 跨句跑出视口时重新选择屏内或双窗口路径；边界无法消费则交还正常跟随。
                if (aligned == null && latestCurrent != index) continue
                request.complete(revision)
                latestAligned()
            } else {
                // 跨屏仍复用原双窗口交接，不长距离扫过整份歌词。
                controller.beginTransition(index,
                    if (index > pane.listState.firstVisibleItemIndex) 1f else -1f,
                    pane.listState.layoutInfo.viewportSize.height.toFloat(), this)?.join()
                request.complete(revision)
                latestAligned()
            }
        }
    }
}
