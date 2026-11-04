package com.musicone

import kotlinx.coroutines.CoroutineScope

/** 自动回焦只在真实落位后交回跟随；跨句失去目标时沿用双窗口动画。 */
internal suspend fun refocusLyricPlayback(
    controller: LyricLayerTransitionController,
    pane: LyricWindowPane,
    refocus: LyricAutoRefocus,
    scope: CoroutineScope,
    current: () -> Int,
    allowed: () -> Boolean,
    onAligned: () -> Unit,
) {
    val list = pane.listState
    while (allowed()) {
        val target = current()
        if (list.layoutInfo.visibleItemsInfo.any { it.index == target }) {
            // 部分露出的实测行也有可靠坐标，不必先换成默认列表动画。
            val aligned = refocus.align(pane, current, requireVisible = false, allowed = allowed)
            if (!allowed()) return
            if (aligned != null) { onAligned(); return }
        } else if (shouldAnimateLyricScroll(list.firstVisibleItemIndex, target)) {
            list.animateScrollToItem(target)
            if (!allowed()) return
            if (current() != target) continue
            pane.playbackStep.consume(target, enabled = true)
            onAligned()
            return
        }
        if (!allowed()) return
        val latest = current()
        controller.beginTransition(latest,
            if (latest > list.firstVisibleItemIndex) 1f else -1f,
            list.layoutInfo.viewportSize.height.toFloat(), scope,
            onTargetReady = onAligned)
        return
    }
}
