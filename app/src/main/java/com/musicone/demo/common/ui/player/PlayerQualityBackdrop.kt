package com.musicone.demo

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.GraphicsLayer

private class PlayerQualityBackdropCapture {
    var ready = false
}

/** 默认只记录菜单打开首帧；关联详情存在时可持续采样当前播放页，菜单自身仍排除在外。 */
internal fun Modifier.playerQualityBackdropSnapshot(layer: GraphicsLayer, enabled: Boolean,
    captureContinuously: Boolean = false): Modifier = composed {
    val capture = remember(layer) { PlayerQualityBackdropCapture() }
    drawWithContent {
        if (enabled && (!capture.ready || captureContinuously)) {
            layer.record { this@drawWithContent.drawContent() }
            capture.ready = true
        } else if (!enabled) {
            capture.ready = false
        }
        drawContent()
    }
}
