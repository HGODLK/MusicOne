package com.musicone.demo

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.GraphicsLayer

private class PlayerQualityBackdropCapture {
    var ready = false
}

/** 仅在菜单打开首帧记录一次完整播放页，菜单本身不进入模糊采样源。 */
internal fun Modifier.playerQualityBackdropSnapshot(layer: GraphicsLayer, enabled: Boolean): Modifier = composed {
    val capture = remember(layer) { PlayerQualityBackdropCapture() }
    drawWithContent {
        if (enabled && !capture.ready) {
            layer.record { this@drawWithContent.drawContent() }
            capture.ready = true
        } else if (!enabled) {
            capture.ready = false
        }
        drawContent()
    }
}
