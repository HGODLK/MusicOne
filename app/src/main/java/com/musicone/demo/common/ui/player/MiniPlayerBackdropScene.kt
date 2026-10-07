package com.musicone.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/** 在实体页转场期间录制页面合成结果，迷你播放器从同一画面连续取样。 */
@Composable
internal fun MiniPlayerBackdropScene(
    layer: GraphicsLayer,
    capture: Boolean,
    onBoundsChanged: (Rect) -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()
        .onGloballyPositioned { onBoundsChanged(it.boundsInRoot()) }
        .drawWithContent {
            if (capture) {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            } else drawContent()
        }, content = content)
}
