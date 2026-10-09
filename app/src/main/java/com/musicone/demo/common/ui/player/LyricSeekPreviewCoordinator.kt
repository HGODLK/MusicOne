package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/** 拖动只处理最新预览句；松手定位完成后再恢复正常跟随。 */
@Composable
internal fun LyricSeekPreviewEffect(
    controller: LyricLayerTransitionController,
    current: Int,
    seeking: Boolean,
    enabled: Boolean,
) {
    val latestCurrent by rememberUpdatedState(current)
    val latestSeeking by rememberUpdatedState(seeking)
    LyricAnimationEffect(controller, enabled) {
        try {
            if (enabled) snapshotFlow { latestSeeking to latestCurrent }.collectLatest { (preview, line) ->
                if (preview || controller.previewActive) {
                    controller.previewTo(line)
                    if (!preview) controller.finishPreview()
                }
            }
        } finally {
            withContext(NonCancellable) { controller.finishPreview() }
        }
    }
}

/** 旧窗口仍按实际混合状态显示，预览目标使用独立列表静默准备，避免更新旧图层引用。 */
@Composable
internal fun LyricSeekPreviewTarget(
    controller: LyricLayerTransitionController,
    lines: List<TimedLyric>,
    anchor: Dp,
    bottomPadding: Dp,
    largeText: Boolean,
) {
    val pane = controller.previewPane ?: return
    androidx.compose.runtime.key(pane.id) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 0f }.drawWithContent {
            drawContent()
            val item = pane.listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == pane.currentLine }
            controller.previewDrawn(pane, item?.index, item?.offset)
        }) {
            LyricListView(pane, lines, anchor, bottomPadding, largeText,
                active = false, isSeeking = true, lyricSettleProgress = { 1f }, onLineClick = { _, _ -> })
        }
    }
}
