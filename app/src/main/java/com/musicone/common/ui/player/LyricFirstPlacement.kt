package com.musicone

import androidx.compose.runtime.*
import kotlinx.coroutines.flow.collectLatest

internal data class LyricPlacementGeometry(val width: Int, val height: Int, val anchor: Int) {
    val measured get() = width > 0 && height > 0
}

/** 首次显示歌词前先等列表滚动到目标行再露出，避免闪一下第 0 行；一旦显示后就不再隐藏。 */
internal class LyricFirstPlacement {
    private var confirmed by mutableStateOf<LyricPlacementGeometry?>(null)
    private var presented by mutableStateOf(false)
    fun ready(geometry: LyricPlacementGeometry): Boolean = presented || (geometry.measured && confirmed == geometry)
    fun visible(geometry: LyricPlacementGeometry, enabled: Boolean, allowed: Boolean): Boolean =
        !enabled || !allowed || ready(geometry)
    fun markPresented() { presented = true }
    fun confirm(geometry: LyricPlacementGeometry, offset: Int?, rowOffset: Float, scrolling: Boolean): Boolean {
        if (!geometry.measured || !lyricWindowAligned(offset, scrolling, rowOffset)) return false
        confirmed = geometry
        return true
    }
}

internal class LyricFirstPlacementGate(val ready: State<Boolean>, val onDrawn: (Boolean) -> Unit)

@Composable
internal fun rememberLyricFirstPlacement(
    pane: LyricWindowPane,
    enabled: Boolean,
    allowed: Boolean,
    current: Int,
): LyricFirstPlacementGate {
    // 状态绑定到当前窗口实例，避免后续重组或重新排版时把已经显示的歌词又藏起来。
    val placement = remember(pane) { LyricFirstPlacement() }
    val latestCurrent by rememberUpdatedState(current)
    val geometry = remember(pane) {
        derivedStateOf {
            val info = pane.listState.layoutInfo
            LyricPlacementGeometry(info.viewportSize.width, info.viewportSize.height, info.beforeContentPadding)
        }
    }
    LyricAnimationEffect(placement, enabled, allowed) {
        if (!enabled || !allowed) return@LyricAnimationEffect
        snapshotFlow { geometry.value }.collectLatest { expected ->
            if (!expected.measured || placement.ready(expected)) return@collectLatest
            // 滚完后核对实测偏移；如果期间换句或视口变了，继续对齐新目标直到真正到位。
            while (geometry.value == expected && !placement.ready(expected)) {
                val target = latestCurrent
                val existing = pane.listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
                if (placement.confirm(expected, existing?.offset, pane.playbackStep.offsetPx(target),
                        pane.listState.isScrollInProgress)) break
                pane.playbackStep.reset(target)
                pane.listState.scrollToItem(target)
                withFrameNanos { }
                val info = pane.listState.layoutInfo
                if (geometry.value == expected && target == latestCurrent && placement.confirm(expected,
                        info.visibleItemsInfo.firstOrNull { it.index == target }?.offset,
                        pane.playbackStep.offsetPx(target), pane.listState.isScrollInProgress)) break
            }
        }
    }
    val ready = remember(placement, geometry, enabled, allowed) {
        derivedStateOf { placement.visible(geometry.value, enabled, allowed) }
    }
    return remember(placement, geometry, ready, pane) {
        LyricFirstPlacementGate(ready) { visible ->
            val info = pane.listState.layoutInfo
            // 如果预备流程或 seek 已经把列表滚到位了，顺手记下已就绪状态，避免交接后又隐藏。
            if (!allowed || pane.currentLine == latestCurrent) {
                placement.confirm(geometry.value, info.visibleItemsInfo.firstOrNull { it.index == pane.currentLine }?.offset,
                    pane.playbackStep.offsetPx(pane.currentLine), pane.listState.isScrollInProgress)
            }
            if (visible && ready.value) placement.markPresented()
        }
    }
}
