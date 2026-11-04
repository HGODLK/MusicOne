package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
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
        if (!enabled) return@LyricAnimationEffect
        val owner = controller.attachPreviewOwner()
        try {
            snapshotFlow { latestSeeking to latestCurrent }.collectLatest { (preview, line) ->
                if (preview || controller.previewActive) {
                    controller.previewTo(line)
                    if (!preview) controller.finishPreview()
                }
            }
        } finally {
            withContext(NonCancellable) { controller.finishPreview(owner) }
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
    typography: LyricTypography,
) {
    val pane = controller.previewPane ?: return
    val density = LocalDensity.current
    val rotation = LocalPhonePlayerRotation.current
    androidx.compose.runtime.key(pane.id) {
        val readiness = remember(pane) { LyricLayoutReadiness() }
        val owner = remember(pane) { Any() }
        BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { alpha = 0f }) {
            val stamp = LyricLayoutStamp(owner, controller.displayRevision, rotation,
                with(density) { maxWidth.roundToPx() }, with(density) { maxHeight.roundToPx() },
                pane.currentLine, lines.getOrNull(pane.currentLine)?.translation != null,
                pane = pane, mount = pane.listHost.revision)
            SideEffect { readiness.expect(stamp) }
            val notify = rememberUpdatedState {
                val item = pane.listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == pane.currentLine }
                if (readiness.geometry(stamp.generation, rotation) != null)
                    controller.previewDrawn(pane, item?.index, item?.offset)
            }
            val proof = remember(stamp) { LyricLayoutProof(readiness, stamp, pane) { notify.value() } }
            Box(Modifier.fillMaxSize().drawWithContent { drawContent(); notify.value() }) {
                LyricListView(pane, lines, anchor, bottomPadding, typography,
                    layoutProof = proof,
                    active = false, isSeeking = true, lyricSettleProgress = { 1f }, onLineClick = { _, _ -> })
            }
        }
    }
}
