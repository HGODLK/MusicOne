package com.musicone

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer

private class PlayerQualityBackdropCapture {
    var ready = false
}

/** 默认只记录菜单打开首帧；实时菜单持续采样当前页面，菜单自身仍排除在外。 */
internal fun Modifier.playerQualityBackdropSnapshot(layer: GraphicsLayer, enabled: Boolean,
    captureContinuously: Boolean = false): Modifier = composed {
    val capture = remember(layer) { PlayerQualityBackdropCapture() }
    drawWithContent {
        if (enabled && (!capture.ready || captureContinuously)) {
            layer.record { this@drawWithContent.drawContent() }
            capture.ready = true
            if (captureContinuously) {
                // 本帧直接显示刚录制的内容，避免实时采样让同一页面绘制两遍。
                drawLayer(layer)
                return@drawWithContent
            }
        } else if (!enabled) {
            capture.ready = false
        }
        drawContent()
    }
}
