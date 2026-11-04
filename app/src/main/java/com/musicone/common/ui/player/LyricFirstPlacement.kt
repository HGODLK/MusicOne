package com.musicone

import androidx.compose.runtime.*
import kotlinx.coroutines.flow.collectLatest

internal data class LyricPlacementGeometry(val width: Int, val height: Int, val anchor: Int) {
    val measured get() = width > 0 && height > 0
}

/** 首次显示前确认布局；显示后只由现有位置所有者接管，不能再隐藏或重置播放弹簧。 */
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
    // 已显示窗口的资格跟随窗口身份，不能因重新排版或播放重组重新隐藏并抢走弹簧。
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
            // 使用最终列表实测结果；若期间换句或改窗，就确认新的目标，不能提前宣布就绪。
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
            // 预备和 seek 自己完成定位时也记下实测结果，不能在交接后重新从未就绪开始。
            if (!allowed || pane.currentLine == latestCurrent) {
                placement.confirm(geometry.value, info.visibleItemsInfo.firstOrNull { it.index == pane.currentLine }?.offset,
                    pane.playbackStep.offsetPx(pane.currentLine), pane.listState.isScrollInProgress)
            }
            if (visible && ready.value) placement.markPresented()
        }
    }
}
