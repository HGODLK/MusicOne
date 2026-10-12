package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer

/** 同一窗口在当前和退出角色之间保留组件，避免寻焦时重复排版、录制和上传来源文字。 */
@Composable
internal fun LyricWindowStages(
    controller: LyricLayerTransitionController,
    onDrawn: (LyricWindowPane) -> Unit,
    content: @Composable (LyricWindowPane, Boolean) -> Unit,
) {
    val current = controller.currentPane
    listOfNotNull(controller.outgoingPane, current).forEach { pane ->
        key(pane.id) {
            val outgoing = pane !== current
            Box(Modifier.fillMaxSize().lyricWindowStage(controller, pane,
                onDrawn = { onDrawn(pane) })) {
                content(pane, outgoing)
            }
        }
    }
}

/** 窗口节点保留绘制内容；寻焦只更新原位移和透明度，就绪回调在节点外观察。 */
@Composable
internal fun Modifier.lyricWindowStage(
    controller: LyricLayerTransitionController,
    pane: LyricWindowPane,
    onDrawn: () -> Unit,
): Modifier {
    val stage = remember(controller, pane) {
        Modifier.graphicsLayer {
            // 独立歌词时钟可能先于 UI 重组发布新目标，旧节点此时仍须按最新身份绘制。
            val role = lyricWindowStageRole(pane.id, controller.currentPane.id, controller.outgoingPane?.id)
            val preparing = controller.state == LyricLayerTransitionState.PREPARING
            translationY = role.translation(preparing, controller.seekOffset.value, controller.seekTravel)
            alpha = role.alpha(preparing, controller.seekOffset.value, controller.seekTravel)
            if (role == LyricWindowStageRole.CURRENT) lyricTracePosition("MusicOne:seek-window-y") { translationY }
        }
    }
    val latestDrawn = rememberUpdatedState(onDrawn)
    val observer = remember(controller, pane, latestDrawn) {
        Modifier.drawWithContent {
            drawContent()
            if (pane === controller.currentPane) latestDrawn.value.invoke()
        }
    }
    return this.then(observer).then(stage)
}
